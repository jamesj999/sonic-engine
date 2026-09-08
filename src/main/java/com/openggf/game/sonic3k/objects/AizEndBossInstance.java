package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.events.S3kAizEventWriteSupport;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.Palette;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnConstructionContextRewindRecreatable;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.SwingMotion;

import java.util.List;
import java.util.logging.Logger;

/**
 * AIZ Act 2 end boss (Object 0x92) — Eggman's fire-breathing machine.
 *
 * <p>ROM: Obj_AIZEndBoss (sonic3k.asm:137997).
 * Emerges from the waterfall, fires flamethrower projectiles via arm/propeller children,
 * then submerges, repositions to one of 4 random positions, and re-emerges. After two
 * attack cycles the boss is defeated, spawning an Egg Capsule.
 *
 * <p>State machine (AIZ_EndBossIndex, routines 0–14 stepping by 2):
 * <ol start="0">
 *   <li>Init: spawn ship + 2 arms (each arm spawns propeller child)</li>
 *   <li>Emerge: flickering reveal from waterfall (animation byte_69D98)</li>
 *   <li>Revealed: animation + becomes hittable, spawns flame column</li>
 *   <li>Hover: sine oscillation (Swing_UpAndDown)</li>
 *   <li>Attack wait: countdown (fire window)</li>
 *   <li>Re-emerge: reuses routine 2 (submerge splash, reposition)</li>
 *   <li>Camera scroll: incrementally move camera min/max X</li>
 *   <li>Move + wait: travel to new random position</li>
 * </ol>
 *
 * <p>Character-specific data:
 * <ul>
 *   <li>Sonic: camera lock at $4880, target $48E0, Y offset $15A</li>
 *   <li>Knuckles: camera lock at $4100, target $4160, Y offset $5DA</li>
 * </ul>
 */
public class AizEndBossInstance extends AbstractBossInstance
        implements SpawnConstructionContextRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizEndBossInstance.class.getName());

    // ===== Routine constants (ROM: AIZ_EndBossIndex, stride 2) =====
    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_EMERGE = 2;
    private static final int ROUTINE_REVEALED = 4;
    private static final int ROUTINE_HOVER = 6;
    private static final int ROUTINE_ATTACK_WAIT = 8;
    private static final int ROUTINE_RE_EMERGE = 10;
    private static final int ROUTINE_CAMERA_SCROLL = 12;
    private static final int ROUTINE_MOVE_WAIT = 14;
    private static final int ROUTINE_DEFEATED = 16;

    // ===== Boss constants =====
    private static final int HIT_COUNT = 8;
    private static final int COLLISION_SIZE = 0x10;     // ROM: ObjDat_AIZEndBoss collision $10
    private static final int COLLISION_FLAGS_ACTIVE = 0x16; // ROM: move.b #$16,collision_flags
    private static final int INVULN_TIME = 0x20;        // ROM: move.b #$20,$20(a0)

    // ===== Character-specific data (ROM: AIZBossSonicDat / AIZBossKnuxDat) =====
    private static final int SONIC_CAMERA_LOCK_X = 0x4880;
    private static final int SONIC_TARGET_X = 0x48E0;
    private static final int SONIC_Y_OFFSET = 0x15A;
    private static final int KNUX_CAMERA_LOCK_X = 0x4100;
    private static final int KNUX_TARGET_X = 0x4160;
    private static final int KNUX_Y_OFFSET = 0x5DA;

    // ===== Timing constants (ROM frame counts) =====
    private static final int WAIT_BEFORE_MUSIC = 120;   // ROM: move.w #$78,$2E
    private static final int HOVER_TIME = 0x1F;          // ROM: move.w #$1F,$2E
    private static final int FIRE_TIME_SONIC = 0x2F;     // ROM: move.w #$2F,$2E
    private static final int FIRE_SIGNAL_WAIT = 0x8F;    // ROM: move.w #$8F,$2E
    private static final int RETREAT_WAIT = 0x3F;         // ROM: move.w #$3F,$2E
    private static final int REPOSITION_TIME = 0x7F;      // ROM: move.w #$7F,$2E
    private static final int POST_DEFEAT_SONIC = 0xBF;    // ROM: move.w #$BF
    private static final int POST_DEFEAT_KNUX = 0xFF;     // ROM: move.w #$FF
    /**
     * ROM {@code BossDefeated}: {@code move.w #$3F,$2E(a0)}
     * (sonic3k.asm:180820-180821). {@code AIZEndBoss_StartDefeatCallback} ends
     * with {@code jmp (BossDefeated_StopTimer).l}, and that label is a single
     * {@code clr.b (Update_HUD_timer).w} which FALLS THROUGH into
     * {@code BossDefeated} — so the defeat path does write {@code $2E}, and the
     * fade-out stage is a fixed $3F rather than the boss's inherited timer.
     * The fall-through is easy to miss: the routine that is jumped to is named
     * for stopping the timer, not for setting this one.
     */
    private static final int BOSS_DEFEATED_WAIT = 0x3F;
    /**
     * ROM {@code loc_85674}: {@code move.w #(2*60)-1,$2E(a0)} — the delay
     * {@code Wait_FadeToLevelMusic} installs on expiry, counted down by
     * {@code Obj_Wait} before the egg capsule is created (sonic3k.asm:179663).
     */
    private static final int FADE_TO_LEVEL_MUSIC_WAIT = (2 * 60) - 1;
    // ===== Swing parameters (ROM: loc_6933A) =====
    private static final int SWING_AMPLITUDE = 0xC0;     // ROM: move.w #$C0,$3E(a0)
    private static final int SWING_INITIAL_VEL = 0xC0;   // ROM: move.w #$C0,y_vel(a0)
    private static final int SWING_ACCEL = 0x10;          // ROM: move.w #$10,$40(a0)

    // ===== Random reposition targets (ROM: word_69AC8) =====
    // Each entry: X offset from _unkFA84, Y offset from _unkFA86
    private static final int[][] REPOSITION_TARGETS = {
            {0x058, 0x76},   // angle 0
            {0x0A0, 0x46},   // angle 4
            {0x160, 0x46},   // angle 8
            {0x1A8, 0x76},   // angle $C
    };

    // ===== Palette flash colors (ROM: sub_69C5C) =====
    // PalLoad_Line1 targets Normal_palette_line_2, which is engine palette index 1.
    // Flash positions within that line: +$08, +$0E, +$12, +$14, +$16, +$1A, +$1C
    // (color indices 4, 7, 9, 10, 11, 13, 14 within the 16-color line)
    private static final int BOSS_PALETTE_INDEX = 1;
    private static final int[] FLASH_PAL_INDICES = {4, 7, 9, 10, 11, 13, 14};
    private static final int[] FLASH_NORMAL_COLORS = {0x0222, 0x0008, 0x004C, 0x0006, 0x0020, 0x0A24, 0x0622};
    private static final int[] FLASH_HIT_COLORS = {0x0AAA, 0x0AAA, 0x0AAA, 0x0CCC, 0x0EEE, 0x0666, 0x0888};

    // ===== Instance state =====
    private int cameraLockX;    // _unkFA82
    private int targetMaxX;     // _unkFA84
    private int yBase;          // _unkFA86

    private int waitTimer = -1;
    private WaitCallback waitCallback = WaitCallback.NONE;
    /** Defers the first camera-bound write until the camera pass after routine $C is installed. */
    private boolean cameraScrollBoundsPending;
    /** Restores the skipped right-bound increment once it can no longer move the camera early. */
    private boolean cameraScrollMaxCatchUpPending;

    private enum WaitCallback {
        NONE,
        START_BOSS_MUSIC,
        ON_EMERGE_COMPLETE,
        BEGIN_HOVER,
        ON_HOVER_COMPLETE,
        ON_FIRE_TIMER_EXPIRED,
        BEGIN_RETREAT,
        BEGIN_RE_SUBMERGE,
        ON_RE_SUBMERGE_COMPLETE,
        LOOP_BACK_TO_EMERGE,
        /** ROM {@code AIZEndBoss_StartDefeat}, installed in {@code $34} by the defeat callback. */
        START_DEFEAT,
        /** ROM {@code AIZEndBoss_StartCapsuleSequence}, installed in {@code $34} by StartDefeat. */
        START_CAPSULE_SEQUENCE
    }

    /** ROM: angle ($26) — selects position index (0, 4, 8, or $C). */
    private int angle;
    /** ROM: bit flags in $38(a0). */
    private int flags38;
    private static final int FLAG_PROPELLER_FIRE = 0x02;  // signals propellers to extend/fire
    private static final int FLAG_EMERGE_STARTED = 0x08;
    private static final int FLAG_HIDDEN = 0x40;           // boss hidden (submerged)
    private static final int FLAG_SECOND_CYCLE = 0x80;     // set after first attack cycle
    private static final int FLAG_DEFEAT_STARTED = 0x10;
    /**
     * ROM parity: sprite orientation lives in render_flags bit 0, not in $38(a0).
     * Keep render-facing state separate so boss-state flag updates do not flip the art.
     */
    private boolean facingRight;

    /** ROM: _unkFAA2 — signals propeller arms that fire window is active. */
    private boolean fireSignalActive;
    /** ROM: _unkFAA3 — signals children that boss is defeated. */
    private boolean defeatSignal;
    /** ROM: _unkFAA8 — set when egg capsule should spawn. */
    private boolean eggCapsuleSignal;

    private boolean collisionEnabled;
    /**
     * The ROM restores {@code collision_flags} from the boss object's update,
     * after that frame's engine touch-response pass has already run.
     */
    private boolean collisionEnablePending;
    private boolean highPriorityArt;
    private int mappingFrame;

    /** Set once when Obj_AIZEndBossMain begins; never cleared. Gates rendering. */
    private boolean renderActivated;

    // Swing state
    private int swingVelocity;
    private boolean swingDown;

    // Defeat sequence
    private S3kBossExplosionController defeatExplosionController;
    private boolean defeatRenderComplete;

    // Children references
    private AizEndBossShipChild shipChild;
    private AizEndBossArmChild leftArm;
    private AizEndBossArmChild rightArm;
    private S3kKosModuleQueue bossArtQueue;
    private HardwareWorkHandle bossArtHandle;
    private long bossArtOrdinal = -1;
    /** Set after the one-shot ROM art load retires; prevents later updates re-queuing it. */
    private boolean bossArtLoaded;

    public AizEndBossInstance(ObjectSpawn spawn) {
        super(spawn, "AIZEndBoss");
        Aiz2BossEndSequenceState.reset();
    }

    // ===== Lifecycle =====

    @Override
    protected void initializeBossState() {
        state.routine = ROUTINE_INIT;
        state.hitCount = HIT_COUNT;
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
        cameraScrollBoundsPending = false;
        cameraScrollMaxCatchUpPending = false;
        defeatRenderComplete = false;
        defeatExplosionController = null;
        fireSignalActive = false;
        defeatSignal = false;
        eggCapsuleSignal = false;
        collisionEnabled = false;
        collisionEnablePending = false;
        highPriorityArt = false;
        mappingFrame = 0;
        renderActivated = false;
        bossArtLoaded = false;
        flags38 = 0;
        facingRight = false;
        angle = 0;
        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(false);

        // Select character-specific data (ROM: Obj_AIZEndBoss character_id check)
        PlayerCharacter character = getPlayerCharacter();
        boolean isKnuckles = (character == PlayerCharacter.KNUCKLES);
        cameraLockX = isKnuckles ? KNUX_CAMERA_LOCK_X : SONIC_CAMERA_LOCK_X;
        targetMaxX = isKnuckles ? KNUX_TARGET_X : SONIC_TARGET_X;
        yBase = isKnuckles ? KNUX_Y_OFFSET : SONIC_Y_OFFSET;
    }

    @Override
    protected int getInitialHitCount() {
        return HIT_COUNT;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // Custom defeat sequence (ROM: loc_69C36 / loc_69482)
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return INVULN_TIME;
    }

    @Override
    protected int getPaletteLineForFlash() {
        return -1; // Custom palette flash (ROM: sub_69C5C, Normal_palette_line_2 / engine index 1)
    }

    @Override
    protected boolean usesBaseHitHandler() {
        return false; // ROM: sub_69BE2 — self-contained flash + timer in updateBossLogic()
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic3kSfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic3kSfx.EXPLODE.id;
    }

    // ===== Collision =====

    @Override
    public int getCollisionFlags() {
        // ROM: collision_flags is cleared during emerge/submerge (clr.b collision_flags)
        // and only set when the boss is fully revealed (move.b #$16,collision_flags).
        // FLAG_HIDDEN means the boss is behind the waterfall — not hittable.
        if (!collisionEnabled || state.invulnerable || state.defeated
                || (flags38 & FLAG_HIDDEN) != 0) {
            return 0;
        }
        // ROM ObjDat_AIZEndBoss starts with collision $10, but the revealed
        // setup overwrites it with $16 before touch is active
        // (sonic3k.asm:138109-138114). Keep the raw enemy-style flag byte:
        // Touch_Enemy treats this as a boss through nonzero collision_property.
        return COLLISION_FLAGS_ACTIVE;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        if (state.invulnerable || state.defeated) {
            return;
        }
        state.hitCount--;
        state.invulnerabilityTimer = INVULN_TIME;
        state.invulnerable = true;
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
        onHitTaken(state.hitCount);

        if (state.hitCount <= 0) {
            state.hitCount = 0;
            state.defeated = true;
            services().gameState().addScore(1000);
            onDefeatStarted();
        }
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // Hit reaction is handled by invulnerability flash in updateCustomFlash()
    }

    // ===== Main update loop =====

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        if (state.routine != ROUTINE_INIT) {
            serviceBossArtQueue();
        }
        if (collisionEnablePending) {
            collisionEnabled = true;
            collisionEnablePending = false;
        }
        if (cameraScrollMaxCatchUpPending && state.routine != ROUTINE_CAMERA_SCROLL) {
            int camMaxX = (services().camera().getMaxX() & 0xFFFF) + 2;
            services().camera().setMaxX((short) camMaxX);
            cameraScrollMaxCatchUpPending = false;
        }
        // Custom palette flash on hit
        if (state.invulnerable) {
            updateCustomFlash();
            state.invulnerabilityTimer--;
            if (state.invulnerabilityTimer <= 0) {
                state.invulnerable = false;
                restoreNormalPalette();
                if (!state.defeated) {
                    collisionEnabled = true;
                }
            }
        }

        switch (state.routine) {
            case ROUTINE_INIT -> updateInit();
            case ROUTINE_EMERGE, ROUTINE_RE_EMERGE -> updateEmerge();
            case ROUTINE_REVEALED -> updateRevealed();
            case ROUTINE_HOVER -> updateHover();
            case ROUTINE_ATTACK_WAIT -> updateAttackWait();
            case ROUTINE_CAMERA_SCROLL -> updateCameraScroll();
            case ROUTINE_MOVE_WAIT -> updateMoveWait();
            case ROUTINE_DEFEATED -> updateDefeated();
        }
    }

    private void serviceBossArtQueue() {
        try {
            if (bossArtLoaded) {
                return;
            }
            if (bossArtQueue == null && bossArtOrdinal >= 0) {
                bossArtQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
                bossArtHandle = services().hardwareTiming().pendingHandle(
                                HardwareWorkKind.KOS_MODULE_QUEUE,
                                bossArtOrdinal)
                        .orElseThrow(() -> new IllegalStateException(
                                "restored AIZ end-boss owner cannot find KosM ordinal "
                                        + bossArtOrdinal));
            }
            if (bossArtHandle == null && bossArtQueue == null) {
                bossArtQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
                bossArtHandle = bossArtQueue.queue(
                        services().rom(),
                        Sonic3kConstants.ART_KOSM_AIZ_END_BOSS_ADDR,
                        Sonic3kConstants.ART_TILE_AIZ_END_BOSS);
                bossArtOrdinal = bossArtHandle.ordinal();
                return;
            }
            if (bossArtHandle != null && bossArtQueue.isReady(bossArtHandle)) {
                bossArtQueue.claim(bossArtHandle);
                bossArtLoaded = true;
                bossArtHandle = null;
                bossArtQueue = null;
                bossArtOrdinal = -1;
            }
        } catch (Exception unavailable) {
            if (bossArtOrdinal >= 0) {
                throw new IllegalStateException(
                        "AIZ end-boss KosM owner lost its submitted job",
                        unavailable);
            }
            // Lightweight object tests can exercise the independent native
            // $78 wait without a session timing/ROM service.
            bossArtQueue = null;
            bossArtHandle = null;
        }
    }

    // ===== Routine implementations =====

    /** ROM: Obj_AIZEndBossWait — Wait for camera to reach arena, then lock and load art. */
    private void updateInit() {
        int cameraX = services().camera().getX();
        if (cameraX < cameraLockX) {
            return;
        }

        // Lock camera at boss arena (ROM: loc_691D4)
        services().camera().setMinX((short) cameraLockX);
        services().camera().setMaxX((short) cameraLockX);
        // ROM: AIZEndBoss_StartArenaLock falls through to LoadEnemyArt at the
        // activation boundary. Do not submit this one-shot owner while a
        // layout entry is still waiting in routine 0; that entry can be
        // unloaded and recreated several times before the arena is reached.
        serviceBossArtQueue();

        // Set Boss_flag to lock screen (ROM: st (Boss_flag).w)
        S3kAizEventWriteSupport.setBossFlag(services(), true);
        services().gameState().setCurrentBossId(0x92);

        // Fade out current music
        services().fadeOutMusic();

        // Load boss palette (ROM: PalLoad_Line1, Pal_AIZEndBoss → palette line 2)
        loadBossPalette();

        // Transition to wait-then-play-music
        waitTimer = WAIT_BEFORE_MUSIC;
        waitCallback = WaitCallback.START_BOSS_MUSIC;
        state.routine = ROUTINE_EMERGE; // Will be overridden after wait
        // But first we wait — handle via the emerge routine checking waitTimer

        // Actually, the ROM flows: lock → Obj_Wait($78) → Obj_AIZEndBossMusic → Obj_AIZEndBossMain
        // Let's use ROUTINE_ATTACK_WAIT as a generic "wait" state for the pre-music delay
        state.routine = ROUTINE_ATTACK_WAIT;
    }

    /** ROM: Obj_AIZEndBossMusic — Play boss music, transition to main init. */
    private void startBossMusic() {
        services().playMusic(Sonic3kMusic.BOSS.id);
        doMainInit();
    }

    /**
     * ROM: Obj_AIZEndBossInit (routine 0) — Set up children and attributes.
     * Called after music starts playing.
     */
    private void doMainInit() {
        // ROM: Obj_AIZEndBossMain is the first code path that calls
        // Draw_And_Touch_Sprite — activate rendering from this point on.
        renderActivated = true;

        // ROM: SetUp_ObjAttributes, ObjDat_AIZEndBoss
        // collision_property = 8 (already set)
        // render_flags bit 0 = 1 (face right)
        facingRight = true;

        // Spawn Robotnik ship child (ROM: Child1_MakeRoboShip, subtype=8, offset 0,-$14)
        spawnShipChild();

        // Spawn 2 arm children (ROM: ChildObjDat_69D18)
        // Left arm at offset +$14, -4 (subtype 0)
        // Right arm at offset -$14, -4 (subtype != 0)
        spawnArmChildren();

        // Begin first emerge sequence (ROM: loc_692A0)
        beginEmerge(false);
    }

    /** ROM: loc_692A0 — Begin submerge/emerge cycle. */
    private void beginEmerge(boolean isReEmerge) {
        state.routine = isReEmerge ? ROUTINE_RE_EMERGE : ROUTINE_EMERGE;
        services().playSfx(Sonic3kSfx.WATERFALL_SPLASH.id);

        // ROM: bset #3,$38 ; bset #6,$38 — child reveal + hidden
        flags38 |= FLAG_EMERGE_STARTED | FLAG_HIDDEN;
        highPriorityArt = false;

        // Clear collision during emergence (ROM: clr.b collision_flags)
        collisionEnabled = false;
        collisionEnablePending = false;

        // ROM sub_69C94 restores the palette and clears both the hit-flash
        // timer ($20) and invulnerable status bit before the waterfall phase.
        cancelHitFlashAndRestoreNormalPalette();

        // Set render-facing from angle using the ROM's render_flags logic.
        facingRight = angle < 8;

        // ChildObjDat_69D2E -> CreateChild1_Normal. Both first emerge and
        // re-emerge use subtype 0; only StartSubmerge writes subtype 2.
        spawnWaterfallChild(0);

        // Set up emerge animation
        emergeAnimFrame = 0;
        emergeAnimTimer = 0;
        waitTimer = -1;
        waitCallback = WaitCallback.ON_EMERGE_COMPLETE;
    }

    // Emerge animation state (ROM: byte_69D98 — flickering between frame $2B and frame 0)
    private int emergeAnimFrame;
    private int emergeAnimTimer;
    // ROM byte_69D98 has thirteen zero-delay raw-animation entries before
    // the $F4 callback to loc_69302 (sonic3k.asm:139089-139104).
    private static final int EMERGE_FLICKER_DURATION = 13;

    /** ROM: loc_692E2 — Emerge animation (flickering reveal). */
    private void updateEmerge() {
        if (waitTimer > 0) {
            waitTimer--;
            return;
        }
        if (waitTimer == 0) {
            waitTimer = -1;
            runWaitCallback();
            return;
        }

        // Animate emerge flicker
        emergeAnimTimer++;
        if (emergeAnimTimer < EMERGE_FLICKER_DURATION) {
            // ROM: alternates between mapping_frame $2B (invisible) and 0 (visible)
            // Every 2 frames toggle visibility
            boolean visible = (emergeAnimTimer % 4) < 2;
            if (visible) {
                flags38 &= ~FLAG_HIDDEN;
                mappingFrame = 0;
            } else {
                flags38 |= FLAG_HIDDEN;
            }
        } else {
            runWaitCallback();
        }
    }

    /** ROM: loc_69302 — Post-emerge: become visible, hittable, spawn flame column. */
    private void onEmergeComplete() {
        state.routine = ROUTINE_REVEALED;
        flags38 &= ~FLAG_HIDDEN;
        highPriorityArt = true;

        // ROM: move.b #$16,collision_flags — becomes hittable
        collisionEnablePending = true;

        // Spawn flame column child (ROM: ChildObjDat_69D36, offset 0,-$30)
        spawnFlameColumnChild();

        // Set callback to transition to hover after revealed animation
        revealedAnimTimer = 0;
        waitCallback = WaitCallback.BEGIN_HOVER;
    }

    private int revealedAnimTimer;
    // ROM byte_69DB3 is consumed by Animate_RawNoSSTMultiDelay: $1B/$00,
    // $1B/$04, $1C/$05, $1D/$06, $00/$00, then $F4 callback
    // (docs/skdisasm/sonic3k.asm:138120-138122,139104-139110,177558-177587).
    private static final int REVEALED_FRAME_1B_END = 5;
    private static final int REVEALED_FRAME_1C_END = 11;
    private static final int REVEALED_FRAME_1D_END = 18;
    private static final int REVEALED_FRAME_VISIBLE_END = 19;

    /** ROM: loc_6932C — Revealed animation. */
    private void updateRevealed() {
        revealedAnimTimer++;
        if (revealedAnimTimer <= REVEALED_FRAME_1B_END) {
            mappingFrame = 0x1B;
        } else if (revealedAnimTimer <= REVEALED_FRAME_1C_END) {
            mappingFrame = 0x1C;
        } else if (revealedAnimTimer <= REVEALED_FRAME_1D_END) {
            mappingFrame = 0x1D;
        } else if (revealedAnimTimer <= REVEALED_FRAME_VISIBLE_END) {
            mappingFrame = 0;
        } else {
            runWaitCallback();
        }
    }

    /** ROM: loc_6933A — Set up hover oscillation. */
    private void beginHover() {
        state.routine = ROUTINE_HOVER;
        waitTimer = HOVER_TIME;
        waitCallback = WaitCallback.ON_HOVER_COMPLETE;

        // ROM: Swing parameters
        swingVelocity = SWING_INITIAL_VEL;
        state.yVel = SWING_INITIAL_VEL;
        swingDown = false;
        // ROM: loc_6933A clears bit 0 in $38(a0), not render_flags(a0).
    }

    /** ROM: loc_69368 — Hovering with sine oscillation. */
    private void updateHover() {
        // ROM: Swing_UpAndDown
        SwingMotion.Result swingResult = SwingMotion.update(
                SWING_ACCEL, swingVelocity, SWING_AMPLITUDE, swingDown);
        swingVelocity = swingResult.velocity();
        swingDown = swingResult.directionDown();
        state.yVel = swingVelocity;

        // ROM: MoveSprite2
        applyVelocity();

        // Countdown and callback
        if (waitTimer > 0) {
            waitTimer--;
        } else if (waitTimer == 0) {
            waitTimer = -1;
            runWaitCallback();
        }
    }

    /** ROM: loc_6937E — Post-hover: signal propellers to fire or prepare for retreat. */
    private void onHoverComplete() {
        // ROM: bset #1,$38 — signal propellers
        flags38 |= FLAG_PROPELLER_FIRE;

        if ((flags38 & FLAG_SECOND_CYCLE) == 0) {
            // First cycle: fire phase
            // ROM: move.w #4,angle ; move.w #$2F,$2E ; callback=loc_693DC
            angle = 4;
            waitTimer = FIRE_TIME_SONIC;
            waitCallback = WaitCallback.ON_FIRE_TIMER_EXPIRED;
        } else {
            // Second cycle (post-defeat path): longer wait
            // ROM: Sonic=$BF, Knuckles=$FF
            PlayerCharacter character = getPlayerCharacter();
            waitTimer = (character == PlayerCharacter.KNUCKLES) ? POST_DEFEAT_KNUX : POST_DEFEAT_SONIC;
            waitCallback = WaitCallback.BEGIN_RETREAT;
        }

        state.routine = ROUTINE_HOVER; // Continue hovering during fire window
    }

    /** ROM: loc_693DC — Fire signal active, propellers spawn projectiles. */
    private void onFireTimerExpired() {
        // ROM: st (_unkFAA2).w — signal propeller arms to fire
        fireSignalActive = true;
        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(true);
        waitTimer = FIRE_SIGNAL_WAIT;
        waitCallback = WaitCallback.BEGIN_RETREAT;
    }

    /** ROM: loc_693C0 — Retreat into water. */
    private void beginRetreat() {
        state.routine = ROUTINE_ATTACK_WAIT;
        waitTimer = RETREAT_WAIT;
        waitCallback = WaitCallback.BEGIN_RE_SUBMERGE;
        // ROM: andi.b #$F5,$38 — clear bits 1 and 3
        flags38 &= ~(FLAG_PROPELLER_FIRE | FLAG_EMERGE_STARTED);
        fireSignalActive = false;
    }

    /** ROM: loc_693FA — Re-submerge (splash + move to new position). */
    private void beginReSubmerge() {
        state.routine = ROUTINE_RE_EMERGE;
        services().playSfx(Sonic3kSfx.WATERFALL_SPLASH.id);

        // Clear collision while submerged
        collisionEnabled = false;
        collisionEnablePending = false;
        // ROM loc_693FA calls sub_69C94 here. An active hit flash must not
        // expire later and restore collision behind the waterfall.
        cancelHitFlashAndRestoreNormalPalette();

        // ChildObjDat_69D2E -> CreateChild1_Normal, then subtype 2 selects the
        // falling-drop callback.
        spawnWaterfallChild(2);

        // Set up emerge animation
        emergeAnimFrame = 0;
        emergeAnimTimer = 0;
        waitCallback = WaitCallback.ON_RE_SUBMERGE_COMPLETE;
    }

    /** ROM: loc_6942A — After submerging, decide next phase. */
    private void onReSubmergeComplete() {
        if ((flags38 & FLAG_SECOND_CYCLE) == 0) {
            // First time: scroll camera, set second cycle flag
            state.routine = ROUTINE_CAMERA_SCROLL;
            flags38 |= FLAG_SECOND_CYCLE;
            cameraScrollBoundsPending = true;
        } else {
            // Second time: move to new position directly
            state.routine = ROUTINE_MOVE_WAIT;
        }

        // ROM: bclr #7,art_tile — clear high priority
        highPriorityArt = false;
        mappingFrame = 0;

        // Pick random reposition target (ROM: loc_69A66)
        selectRandomPosition();

        waitCallback = WaitCallback.LOOP_BACK_TO_EMERGE;
    }

    /** ROM: loc_69456 — Incrementally scroll camera right during reposition. */
    private void updateCameraScroll() {
        if (cameraScrollBoundsPending) {
            // loc_6942A installs routine $C during the boss object's pass. The
            // camera has already consumed this frame's bounds, so loc_69456's
            // first +2 becomes camera-visible on the following pass. Boss
            // movement still begins now through loc_6946A/MoveSprite2.
            cameraScrollBoundsPending = false;
            cameraScrollMaxCatchUpPending = true;
        } else {
            // ROM: addq.w #2,(Camera_min_X_pos) until >= _unkFA84
            int camMinX = services().camera().getMinX() & 0xFFFF;
            if (camMinX < targetMaxX) {
                camMinX += 2;
                if (camMinX > targetMaxX) {
                    camMinX = targetMaxX;
                }
                services().camera().setMinX((short) camMinX);
            }
            int camMaxX = (services().camera().getMaxX() & 0xFFFF) + 2;
            services().camera().setMaxX((short) camMaxX);
        }

        // Also do move+wait
        updateMoveWait();
    }

    /** ROM: loc_6946A — Move toward target position and count down wait timer. */
    private void updateMoveWait() {
        applyVelocity();

        if (waitTimer > 0) {
            waitTimer--;
        } else if (waitTimer == 0) {
            waitTimer = -1;
            runWaitCallback();
        }
    }

    /** ROM: loc_69476 — Loop back to emerge for next attack cycle. */
    private void loopBackToEmerge() {
        state.xVel = 0;
        state.yVel = 0;
        beginEmerge(true);
    }

    /** ROM: loc_693F0 — Generic wait state (countdown + hit check). */
    private void updateAttackWait() {
        if (waitTimer > 0) {
            waitTimer--;
        } else if (waitTimer == 0) {
            waitTimer = -1;
            runWaitCallback();
        }
    }

    // ===== Defeat sequence =====

    @Override
    protected void onDefeatStarted() {
        state.routine = ROUTINE_DEFEATED;
        state.xVel = 0;
        state.yVel = 0;
        // ROM AIZEndBoss_StartDefeatCallback installs Wait_FadeToLevelMusic in
        // (a0) and AIZEndBoss_StartDefeat in $34, then tail-jumps through
        // BossDefeated_StopTimer, which falls through into BossDefeated and
        // sets $2E to $3F (sonic3k.asm:138945-138951, 180814-180821).
        waitTimer = BOSS_DEFEATED_WAIT;
        waitCallback = WaitCallback.START_DEFEAT;
        flags38 |= FLAG_DEFEAT_STARTED;
        // ROM: loc_47A74 — bset #7,art_tile hides the boss machine body.
        // The Robotnik ship child (AizEndBossShipChild) keeps rendering independently.
        flags38 |= FLAG_HIDDEN;
        highPriorityArt = true;
        collisionEnabled = false;
        collisionEnablePending = false;
        mappingFrame = 0;

        defeatSignal = true;
        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(false);

        // ROM loc_69C36: jmp (BossDefeated_StopTimer).l (sonic3k.asm:139001).
        stopLevelTimerOnBossDefeat();

        // ROM: The ship child (Obj_RobotnikShip) creates its own explosion controller
        // via Child6_CreateBossExplosion subtype 4 at loc_460DC. In the engine we keep
        // this on the boss for simplicity — subtype 0 produces the same visual effect.
        defeatExplosionController = new S3kBossExplosionController(state.x, state.y, 0, services().rng());
        services().fadeOutMusic();
        if (services().objectManager() != null) {
            spawnDebris();
        }
    }

    /**
     * ROM {@code loc_85674} into {@code AIZEndBoss_StartDefeat}: when
     * {@code Wait_FadeToLevelMusic} expires it installs the fixed
     * {@code (2*60)-1} delay, starts the music fade, and tail-jumps through
     * {@code $34} in the same frame, switching the boss to {@code Obj_Wait}
     * with the capsule sequence as its callback
     * (sonic3k.asm:179661-179669, 138240-138247).
     */
    private void startDefeat() {
        waitTimer = FADE_TO_LEVEL_MUSIC_WAIT;
        waitCallback = WaitCallback.START_CAPSULE_SEQUENCE;
    }

    private void updateDefeated() {
        if (defeatExplosionController != null && !defeatExplosionController.isFinished()) {
            defeatExplosionController.tick();
            spawnPendingExplosions();
        }

        // ROM Wait_FadeToLevelMusic (sonic3k.asm:179656-179660) and Obj_Wait
        // (sonic3k.asm:177949-177952) are the same shape -- subq.w #1,$2E then
        // bmi through $34 -- so both defeat stages are one countdown over the
        // one timer, differing only in which callback is armed. The countdown
        // stops once the capsule sequence has run, because ROM
        // AIZEndBoss_StartCapsuleSequence installs
        // AIZEndBoss_StartPostDefeatCutscene in (a0) and the boss leaves
        // Obj_Wait entirely (sonic3k.asm:138248-138249).
        if (waitCallback != WaitCallback.NONE) {
            waitTimer--;
            if (waitTimer < 0) {
                runWaitCallback();
            }
        }
    }

    /** ROM: loc_694A4/loc_694AA — Spawn Egg Capsule and clear Boss_flag. */
    private void spawnEggCapsuleAndFinish() {
        eggCapsuleSignal = true;
        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(false);

        // Clear Boss_flag (ROM: clr.b (Boss_flag).w)
        S3kAizEventWriteSupport.setBossFlag(services(), false);
        services().gameState().setCurrentBossId(0);

        // ROM: AfterBoss_AIZ2 — load fire palette.
        loadAfterBossArt();

        // ROM: loc_694D4 calls Restore_LevelMusic — play zone music while
        // the egg capsule floats down.
        try {
            int levelMusic = services().getCurrentLevelMusicId();
            if (levelMusic > 0) {
                services().playMusic(levelMusic);
            }
        } catch (Exception e) {
            // Ignore audio errors
        }

        if (getPlayerCharacter() != PlayerCharacter.KNUCKLES) {
            Aiz2BossEndSequenceState.reset();
            AizZoneRuntimeState aizState = aizRuntimeStateOrNull();
            if (aizState != null) {
                aizState.resetBossEndSequenceDispatchState();
            }
            // ROM loc_694AA creates the route-8 capsule through
            // CreateChild6_Simple, which allocates after the current boss
            // slot (sonic3k.asm:138247-138255, 177114-177129).
            spawnChild(() -> Aiz2EndEggCapsuleInstance.createForCamera(
                    services().camera().getX(), services().camera().getY()));
            Aiz2BossEndSequenceState.activateCutsceneOverrideObjects();
            // These two replacements stand in for AIZ2 layout objects, so they
            // must reproduce the layout's relative SST order. The AIZ2 (zone 0,
            // act 2) sprite list read from the user-supplied ROM through
            // Sonic3kObjectPlacement holds Obj_CutsceneButton (id $83) at
            // x=$4B18 immediately BEFORE Obj_AIZDrawBridge (id $32) at x=$4B48,
            // and Obj_Load allocates ascending free slots in that order. The
            // button therefore always occupies the lower slot and is reached
            // first in every object scan, which is why the bridge's
            // AIZDrawBridge_WaitCollapseTrigger observes the button's
            // st (_unkFAA9).w on the same frame the button sets it
            // (sonic3k.asm:59622-59628, 133936-133953).
            S3kCutsceneButtonObjectInstance cutsceneButton = spawnFreeChild(
                    S3kCutsceneButtonObjectInstance::createCutsceneOverride);
            AizDrawBridgeObjectInstance cutsceneBridge = spawnFreeChild(
                    AizDrawBridgeObjectInstance::createCutsceneOverride);
            if (aizState != null) {
                aizState.setButtonBeforeBridgeDispatch(
                        cutsceneButton != null && cutsceneBridge != null
                                && cutsceneButton.getSlotIndex() < cutsceneBridge.getSlotIndex());
            }
            spawnFreeChild(() -> new Aiz2BossEndSequenceController(targetMaxX, yBase));
        } else {
            int newMaxX = targetMaxX + 0x158;
            services().camera().setMaxX((short) newMaxX);
            spawnFreeChild(() -> new S3kBossDefeatSignpostFlow(
                    state.x, 1, S3kBossDefeatSignpostFlow.CleanupAction.NONE));
        }

        // Mark render as complete since boss is now hidden
        defeatRenderComplete = true;
    }

    private void spawnDebris() {
        for (int i = 0; i < AizEndBossDebrisChild.DEBRIS_OFFSETS.length; i++) {
            int debrisIndex = i;
            int x = state.x + AizEndBossDebrisChild.DEBRIS_OFFSETS[debrisIndex][0];
            int y = state.y + AizEndBossDebrisChild.DEBRIS_OFFSETS[debrisIndex][1];
            spawnFreeChild(() -> new AizEndBossDebrisChild(x, y, debrisIndex));
        }
    }

    // ===== Random position selection (ROM: loc_69A66) =====

    private void selectRandomPosition() {
        int newAngle;
        var rng = services().rng();
        do {
            // ROM loc_69A66 calls Random_Number and masks the raw word with #$C,
            // then rejects repeats (sonic3k.asm:138748-138756).
            newAngle = rng.nextBits(0x0C); // 0, 4, 8, or $C
        } while (newAngle == angle);
        angle = newAngle;

        int targetIndex = angle / 4;
        int targetX = targetMaxX + REPOSITION_TARGETS[targetIndex][0];
        int targetY = yBase + REPOSITION_TARGETS[targetIndex][1];

        // ROM loc_69A66 subtracts the full longword x_pos/y_pos, doubles the
        // 16.16 delta, then takes the high word as velocity
        // (sonic3k.asm:138756-138771). This preserves subpixel phase from the
        // prior hover when the boss dives and repositions.
        state.xVel = velocityTowardTargetLongword(targetX, state.xFixed);
        state.yVel = velocityTowardTargetLongword(targetY, state.yFixed);

        waitTimer = REPOSITION_TIME;

        // ROM: loc_69A66 updates render_flags bit 0 from the travel direction.
        facingRight = state.xVel >= 0;
    }

    // ===== Velocity & position helpers =====

    private void applyVelocity() {
        // ROM MoveSprite2 adds signed velocity << 8 to the full longword
        // position, not just an 8-bit fractional byte (sonic3k.asm:36053-36061).
        state.xFixed += state.xVel << 8;
        state.yFixed += state.yVel << 8;
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;
    }

    private static int velocityTowardTargetLongword(int target, int currentLongword) {
        long targetLongword = (long) target << 16;
        long delta = targetLongword - currentLongword;
        return (int) ((delta << 1) >> 16);
    }

    // ===== Custom palette flash (ROM: sub_69C5C + sub_69BE2) =====

    /**
     * ROM: sub_69C5C — Custom palette flash on palette line 2.
     * Alternates between normal and flash colors based on invulnerability timer bit 0.
     */
    private void updateCustomFlash() {
        if (!state.invulnerable) return;
        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= BOSS_PALETTE_INDEX) return;

        boolean flash = (state.invulnerabilityTimer & 1) == 0;
        int[] colors = flash ? FLASH_HIT_COLORS : FLASH_NORMAL_COLORS;
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                level,
                services().graphicsManager(),
                S3kPaletteOwners.AIZ_END_BOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                BOSS_PALETTE_INDEX,
                FLASH_PAL_INDICES,
                colors);
    }

    private void restoreNormalPalette() {
        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= BOSS_PALETTE_INDEX) return;

        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                level,
                services().graphicsManager(),
                S3kPaletteOwners.AIZ_END_BOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                BOSS_PALETTE_INDEX,
                FLASH_PAL_INDICES,
                FLASH_NORMAL_COLORS);
    }

    private void cancelHitFlashAndRestoreNormalPalette() {
        restoreNormalPalette();
        state.invulnerabilityTimer = 0;
        state.invulnerable = false;
    }

    private void loadBossPalette() {
        try {
            byte[] line = services().rom().readBytes(
                    Sonic3kConstants.PAL_AIZ_END_BOSS_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    S3kPaletteOwners.AIZ_END_BOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    BOSS_PALETTE_INDEX,
                    line);
        } catch (Exception e) {
            LOG.fine(() -> "AizEndBossInstance.loadBossPalette: " + e.getMessage());
        }
    }

    /**
     * ROM: AfterBoss_AIZ2 (sonic3k.asm:176563-176567).
     * Restores the fire palette to palette line 1 and reloads the
     * PLC_AfterMiniboss_AIZ art (ArtNem_AIZMisc2 etc.) to refresh
     * level patterns that may have been overwritten by boss art.
     * This ensures the draw bridge and other post-boss objects have
     * correct graphics.
     */
    private void loadAfterBossArt() {
        try {
            // ROM: AfterBoss_AIZ2 — PalLoad_Line1 with Pal_AIZFire.
            // Restores palette line 1 from the boss palette to fire colours.
            // Boss art uses standalone sprite sheets (not level patterns), so
            // level tiles are not corrupted and no PLC reload is needed.
            byte[] firePal = services().rom().readBytes(
                    Sonic3kConstants.PAL_AIZ_FIRE_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    S3kPaletteOwners.AIZ_END_BOSS,
                    S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                    BOSS_PALETTE_INDEX,
                    firePal);
        } catch (Exception e) {
            LOG.fine(() -> "AizEndBossInstance.loadAfterBossArt: " + e.getMessage());
        }
    }

    // ===== Child spawning =====

    private void spawnShipChild() {
        shipChild = spawnChild(() -> new AizEndBossShipChild(this));
        childComponents.add(shipChild);
    }

    private void spawnArmChildren() {
        // Left arm (subtype 0): offset +$14, -4
        leftArm = spawnChild(() -> new AizEndBossArmChild(this, 0x14, -4, 0));
        childComponents.add(leftArm);

        // Right arm (subtype 1): offset -$14, -4
        rightArm = spawnChild(() -> new AizEndBossArmChild(this, -0x14, -4, 1));
        childComponents.add(rightArm);
    }

    private void spawnFlameColumnChild() {
        spawnChild(() -> new AizEndBossFlameColumnChild(this));
    }

    private void spawnWaterfallChild(int subtype) {
        spawnChild(() -> new AizEndBossWaterfallChild(this, subtype));
    }

    void rewindAttachShipChild(AizEndBossShipChild shipChild) {
        this.shipChild = shipChild;
        if (shipChild != null && !childComponents.contains(shipChild)) {
            childComponents.add(shipChild);
        }
    }

    void rewindAttachArmChild(AizEndBossArmChild armChild) {
        if (armChild == null) {
            return;
        }
        if (armChild.rewindSubtype() == 0) {
            leftArm = armChild;
        } else {
            rightArm = armChild;
        }
        if (!childComponents.contains(armChild)) {
            childComponents.add(armChild);
        }
    }

    private void spawnPendingExplosions() {
        if (defeatExplosionController == null) return;

        for (var exp : defeatExplosionController.drainPendingExplosions()) {
            spawnChild(() -> new S3kBossExplosionChild(exp.x(), exp.y()));
            if (exp.playSfx()) {
                services().playSfx(Sonic3kSfx.EXPLODE.id);
            }
        }
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || defeatRenderComplete) return;
        // ROM: Draw_And_Touch_Sprite is only called from Obj_AIZEndBossMain, which
        // is not reached until after Obj_Wait + Obj_AIZEndBossMusic + doMainInit().
        // Before that, the object just does rts — completely invisible.
        if (!renderActivated) return;
        if ((flags38 & FLAG_HIDDEN) != 0) return;

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) return;

        boolean hFlip = facingRight;
        renderer.drawFrameIndex(mappingFrame, state.x, state.y, hFlip, false);
    }

    @Override
    public boolean isHighPriority() {
        return highPriorityArt;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: ObjDat_AIZEndBoss priority $0280 → $280/$80 = bucket 5
        return 5;
    }

    /**
     * ROM parity: boss objects do not call the {@code out_of_range} macro
     * once activated. However, the AIZ2 layout contains TWO 0x92 entries —
     * one for Sonic's arena ($48A0,$1C0) and one for Knuckles' ($4120,$640).
     * Only one is valid per playthrough. The wrong-character boss must be
     * allowed to expire via normal out-of-range while still in ROUTINE_INIT
     * (waiting for the camera to reach the arena). Once activated, the boss
     * becomes persistent to survive camera wrapping during the fight.
     */
    @Override
    public boolean isPersistent() {
        // Only persist after activation — ROUTINE_INIT bosses at the wrong
        // character's position will be cleaned up by out-of-range naturally.
        return state.routine != ROUTINE_INIT;
    }

    // ===== Accessors for children =====

    public boolean isFireSignalActive() {
        return fireSignalActive;
    }

    public boolean isPropellerFireRequested() {
        return (flags38 & FLAG_PROPELLER_FIRE) != 0;
    }

    public void clearPropellerFire() {
        flags38 &= ~FLAG_PROPELLER_FIRE;
    }

    public boolean isDefeatSignal() {
        return defeatSignal;
    }

    /** ROM: $38 bit 4 — set when defeat phase 1 begins (signals ship child to escape). */
    public boolean isDefeatStarted() {
        return (flags38 & FLAG_DEFEAT_STARTED) != 0;
    }

    public boolean isHidden() {
        return (flags38 & FLAG_HIDDEN) != 0;
    }

    public boolean isFacingRight() {
        return facingRight;
    }

    public int getAngle() {
        return angle;
    }

    public int getMappingFrame() {
        return mappingFrame;
    }

    public boolean hasEmergeStarted() {
        return (flags38 & FLAG_EMERGE_STARTED) != 0;
    }

    // ===== Helpers =====

    private void runWaitCallback() {
        if (waitCallback == WaitCallback.NONE) {
            return;
        }
        WaitCallback callback = waitCallback;
        waitCallback = WaitCallback.NONE;
        switch (callback) {
            case START_BOSS_MUSIC -> startBossMusic();
            case ON_EMERGE_COMPLETE -> onEmergeComplete();
            case BEGIN_HOVER -> beginHover();
            case ON_HOVER_COMPLETE -> onHoverComplete();
            case ON_FIRE_TIMER_EXPIRED -> onFireTimerExpired();
            case BEGIN_RETREAT -> beginRetreat();
            case BEGIN_RE_SUBMERGE -> beginReSubmerge();
            case ON_RE_SUBMERGE_COMPLETE -> onReSubmergeComplete();
            case LOOP_BACK_TO_EMERGE -> loopBackToEmerge();
            case START_DEFEAT -> startDefeat();
            case START_CAPSULE_SEQUENCE -> spawnEggCapsuleAndFinish();
            case NONE -> {}
        }
    }

    // Package-private: accessed by AizEndBossPropellerChild for the Knuckles multi-fire check.
    PlayerCharacter getPlayerCharacter() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration());
    }

    private AizZoneRuntimeState aizRuntimeStateOrNull() {
        var registry = services().zoneRuntimeRegistry();
        return registry == null ? null
                : registry.currentAs(AizZoneRuntimeState.class).orElse(null);
    }
}
