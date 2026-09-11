package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.game.sonic3k.objects.S3kBossExplosionController;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.SongFadeTransitionInstance;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kZoneRuntimeState;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractResultsScreen;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.resources.CompressionType;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * LBZ2 Robotnik ship + hanging laser turret boss.
 *
 * <p>ROM: {@code Obj_LBZFinalBoss1} at {@code sonic3k.asm:151927}. The ship
 * docks above a three-segment turret column whose laser heads sweep an arc
 * and fire horizontal bolts; an orbiting spiked pod circles the assembly.
 * The Sonic/Tails defeat branch runs the full Death Egg launch finale through
 * the results tally, auto-walk, look-up, and the MHZ transition.
 */
public final class LbzFinalBoss1Instance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, SpawnRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(LbzFinalBoss1Instance.class.getName());

    private static final String PALETTE_OWNER = "s3k.lbz.finalBoss1";
    private static final int OBJECT_ID = 0xCA;
    private static final int ROUTINE_WAIT = 0x02;
    private static final int ROUTINE_SHUTTLE = 0x04;
    private static final int ROUTINE_HOLD = 0x06;
    private static final int ROUTINE_RECOIL_WAIT = 0x08;
    private static final int ROUTINE_RECOIL_DROP = 0x0A;
    private static final int INITIAL_HP = 9;
    private static final int COLLISION_FLAGS = 0x0F;
    private static final int BODY_FRAME = 0x0C;
    private static final int DEFEAT_FRAME = 5;
    /** ROM word_736F0/word_73704: make_art_tile(ArtTile_LBZFinalBoss1,0,1). */
    private static final int LASER_AND_THRUSTER_PALETTE_LINE = 0;
    private static final int ACTIVATION_TIMER = 0x7F;
    private static final int HIT_INVULNERABILITY = 0x20;
    private static final int HIT_RECOIL_WAIT = 0x0F;
    /** ROM loc_72B04: $40 = 4 -> five y += 8 drops before routine 6. */
    private static final int HIT_RECOIL_DROP_FRAMES = 4;
    private static final int SINK_TIMER = 0x3F;
    private static final int POST_RESULTS_DELAY = 0x1F;
    /** ROM BossDefeated: moveq #100 -> HUD_AddToScore works in tens. */
    private static final int DEFEAT_SCORE = 1000;
    /** ROM $38 detach bits: 0 = top segment (HP 5), 1 = mid (HP 1), 2 = bottom (defeat). */
    private static final int FLAG_DETACH_TOP = 1;
    private static final int FLAG_DETACH_MID = 1 << 1;
    private static final int FLAG_DETACH_BOTTOM = 1 << 2;
    private static final int FLAG_LASER_FIRING_NOTCH = 1 << 3;
    private static final int FLAG_FINAL_BOSS_2_HANDOFF = 1 << 5;
    private static final int STATUS_HIT_SHIFT = 1 << 5;
    private static final int STATUS_HIT_FLASH = 1 << 6;
    private static final int STATUS_DEFEATED = 1 << 7;
    /** ROM sub_73604: Normal_palette_line_2 byte offsets 8/$1C = colour indices 4/14. */
    private static final int[] FLASH_COLOR_INDICES = {4, 14};
    private static final int[] FLASH_NORMAL_WORDS = {0x0026, 0x0020};
    private static final int[] FLASH_WHITE_WORDS = {0x0EEE, 0x0EEE};
    /*
     * ROM byte_7386A/byte_73874. Byte 0 is the shared delay ($05);
     * Animate_ExternalPlayerSprite advances anim_frame by two before looking up
     * the first mapping, so the table's pre-script $C4 mapping is never emitted
     * here. Each following mapping/flip pair lasts six dispatches. The terminal
     * zero mapping transfers control without changing the displayed frame.
     */
    private static final ExternalPlayerFrame[] SONIC_LAUNCH_LOOK_FRAMES = {
            new ExternalPlayerFrame(5, 0x55, Direction.RIGHT),
            new ExternalPlayerFrame(5, 0x59, Direction.LEFT),
            new ExternalPlayerFrame(5, 0x5A, Direction.LEFT)
    };
    private static final ExternalPlayerFrame[] SIDEKICK_LAUNCH_LOOK_FRAMES = {
            new ExternalPlayerFrame(5, 0x55, Direction.RIGHT),
            new ExternalPlayerFrame(5, 0x59, Direction.LEFT),
            new ExternalPlayerFrame(5, 0x5A, Direction.LEFT),
            new ExternalPlayerFrame(5, 0x5A, Direction.LEFT),
            new ExternalPlayerFrame(5, 0x5A, Direction.LEFT),
            new ExternalPlayerFrame(5, 0x5A, Direction.LEFT),
            new ExternalPlayerFrame(5, 0x5A, Direction.LEFT)
    };

    private int x;
    private int y;
    private int xSub;
    private int ySub;
    private int xVel;
    private int yVel;
    private int routine = ROUTINE_WAIT;
    private int hp = INITIAL_HP;
    private int collisionFlags = COLLISION_FLAGS;
    private int collisionBackup = COLLISION_FLAGS;
    private boolean pendingHitResolution;
    private int shiftedSegmentTrailAllocationMisses;
    private int detachedSegmentTrailAllocationMisses;
    private int mappingFrame = BODY_FRAME;
    private int activationTimer = ACTIVATION_TIMER;
    private int flags;
    private int statusBits;
    private int savedRoutine;
    private int invulnerabilityTimer;
    private int recoilTimer;
    private int recoilDropTimer;
    /** ROM $3C: next detach bit index (0 = top, 1 = mid, 2 = bottom). */
    private int detachCount;
    private boolean renderXFlip;
    private boolean initialized;
    private boolean bossMusicStarted;
    private boolean deferChildrenSameFrame;
    /** The allocation pass has built the graph; the next dispatch is still ROM init. */
    private boolean nativeInitPassPending;
    private boolean finalBossPaletteLoaded;
    private boolean resultsComplete;
    private boolean launchMilestoneA;
    private boolean launchMilestoneB;
    private boolean playersReadyForResults;
    private boolean engineFlamesSpawned;
    private boolean bossExplosionPlcQueued;
    private boolean deathEggSmallArtQueued;
    @RewindTransient(reason = "queue facade is rebound from the captured KosM ordinal")
    private S3kKosModuleQueue deathEggSmallArtQueue;
    @RewindTransient(reason = "hardware handle is rebound from the captured KosM ordinal")
    private HardwareWorkHandle deathEggSmallArtHandle;
    private long deathEggSmallArtOrdinal = -1;
    private boolean deathEggSmallArtLoaded;
    private boolean cutsceneAnchorRegistered;
    private int mainExternalFrameTimer = -1;
    private int mainExternalFrameIndex;
    private int sidekickExternalFrameTimer = -1;
    private int sidekickExternalFrameIndex;
    private FinalePhase finalePhase = FinalePhase.NONE;
    private int finaleTimer = -1;
    private final List<SpawnRecord> spawned = new ArrayList<>();

    public enum ChildKind {
        ROBOTNIK_HEAD,
        TOP_ATTACHMENT,
        TURRET_SEGMENT,
        LASER_HEAD,
        MUZZLE_LASER,
        LASER_TRAIL,
        ORBITING_POD,
        GUN_POD,
        HIT_SPARK,
        DEBRIS,
        BOSS_EXPLOSION,
        RESULTS_SCREEN,
        P2_ENDING_POSE_WATCHER,
        TAILS_CPU_RELEASE,
        ENGINE_FLAME,
        EXPLOSION_SEQUENCER,
        DEATH_EGG_MINIATURE,
        DEATH_EGG_SMOKE,
        MUSIC_FADE,
        FINAL_BOSS_2_HANDOFF
    }

    public enum FinalePhase {
        NONE,
        SINK,
        WAIT_PLAYER_READY,
        WAIT_RESULTS_COMPLETE,
        POST_RESULTS_DELAY,
        AUTOWALK,
        WAIT_LAUNCH_MILESTONE_A,
        LOOK_UP,
        WAIT_LAUNCH_MILESTONE_B,
        FINAL_FALL,
        KNUCKLES_HANDOFF
    }

    public LbzFinalBoss1Instance(ObjectSpawn spawn) {
        super(spawn, "LBZFinalBoss1");
        this.x = spawn.x() & 0xFFFF;
        this.y = spawn.y() & 0xFFFF;
    }

    @Override
    public int getX() {
        return x & 0xFFFF;
    }

    @Override
    public int getY() {
        return y & 0xFFFF;
    }

    public int getCentreX() {
        return getX();
    }

    public int getCentreY() {
        return getY();
    }

    public void setCentreX(int x) {
        this.x = x & 0xFFFF;
        this.xSub = 0;
        updateDynamicSpawn(getX(), getY());
    }

    public void setCentreY(int y) {
        this.y = y & 0xFFFF;
        this.ySub = 0;
        updateDynamicSpawn(getX(), getY());
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return hp;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // ROM sub_734FA moves the ship before Add_SpriteToCollisionResponseList.
        // The following player slot therefore sees the published post-move position,
        // rather than the routine-entry coordinates retained in preUpdateX/Y.
        return true;
    }

    @Override
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        if (hp <= 0 || invulnerabilityTimer > 0 || collisionFlags == 0) {
            return;
        }
        hp = Math.max(0, hp - 1);
        collisionFlags = 0;
        pendingHitResolution = true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        serviceDeathEggSmallArtQueue();
        boolean wasInitialized = initialized;
        ensureInitialized();
        if (!wasInitialized) {
            updateDynamicSpawn(getX(), getY());
            return;
        }
        if (nativeInitPassPending) {
            // initializeOnAllocationBeforeParentRelease performs the native init side effects
            // early so the allocator sees the same child graph. The boss slot is below the
            // allocating ship, so its first actual dispatch is still the following pass's
            // init entry and must not consume the Obj_Wait counter (sonic3k.asm:152008-152037).
            nativeInitPassPending = false;
            updateDynamicSpawn(getX(), getY());
            return;
        }
        if (finalePhase != FinalePhase.NONE) {
            updateFinale(player);
            updateDynamicSpawn(getX(), getY());
            return;
        }
        switch (routine) {
            case ROUTINE_WAIT -> updateActivationWait();
            case ROUTINE_SHUTTLE -> updateVerticalShuttle();
            case ROUTINE_HOLD -> {
                // Static hit-stun hold.
            }
            case ROUTINE_RECOIL_WAIT -> updateRecoilWait();
            case ROUTINE_RECOIL_DROP -> updateRecoilDrop();
            default -> {
                // Keep unknown test-forced routines inert instead of inventing behavior.
            }
        }
        resolvePendingHit();
        updateHitFlash();
        updateDynamicSpawn(getX(), getY());
    }

    /**
     * ROM Obj_LBZFinalBoss1 dispatches its current routine before sub_734FA
     * observes the collision_flags clear written by the earlier player slot.
     */
    private void resolvePendingHit() {
        if (!pendingHitResolution) {
            return;
        }
        pendingHitResolution = false;
        if (hp == 0) {
            startDefeat();
            return;
        }
        startHitReaction();
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        collisionFlags = COLLISION_FLAGS;
        collisionBackup = COLLISION_FLAGS;
        hp = INITIAL_HP;
        mappingFrame = BODY_FRAME;
        // ROM loc_729DE: Boss_flag = 1 for every character.
        if (services().gameState() != null) {
            services().gameState().setCurrentBossId(OBJECT_ID);
        }
        ensureBossArtLoaded();
        if (currentPlayerCharacter() != PlayerCharacter.KNUCKLES) {
            activationTimer = ACTIVATION_TIMER;
            // ROM: st (Update_HUD_timer).w — the timer stopped at the EndBoss defeat.
            if (services().levelGamestate() != null) {
                services().levelGamestate().resumeTimer();
            }
            if (!bossMusicStarted) {
                // ROM loc_729DE calls AllocateObject before CreateChild1_Normal.  This is
                // FindFreeObj semantics, so the fade must take the lowest available SST
                // slot rather than being appended after the boss.
                SongFadeTransitionInstance fade = spawnFreeChild(
                        () -> new SongFadeTransitionInstance(
                                90, Sonic3kMusic.BOSS.id, false, deferChildrenSameFrame));
                recordChild(ChildKind.MUSIC_FADE, fade);
                bossMusicStarted = true;
            }
        }
        spawnInitialChildren();
        deferChildrenSameFrame = false;
    }

    /**
     * Runs the ROM init boundary immediately after AllocateObject returns and
     * before the allocating ship releases its SST slot. The object itself will
     * still be dispatched on the following pass because its slot is behind the
     * current ship slot; its newly-created children are likewise held until that
     * pass by {@link BossChild#skipsSameFrameUpdateAfterSpawn()}.
     */
    public void initializeOnAllocationBeforeParentRelease() {
        if (initialized) {
            return;
        }
        nativeInitPassPending = true;
        deferChildrenSameFrame = true;
        ensureInitialized();
    }

    /** ROM: Load_PLC $71 (LBZFinalBoss1 + BossExplosion; ship art from PLC $77). */
    private void ensureBossArtLoaded() {
        if (services().renderManager() != null
                && services().renderManager().getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.LBZ_FINAL_BOSS_1);
            provider.ensureBossExplosionArtLoaded();
        }
    }

    private void spawnInitialChildren() {
        recordChild(ChildKind.ROBOTNIK_HEAD, spawnChild(() -> new RobotnikHeadChild(this)));
        recordChild(ChildKind.TOP_ATTACHMENT, spawnChild(() -> new TopAttachmentChild(this)));
        recordChild(ChildKind.TURRET_SEGMENT, spawnChild(() -> new TurretSegmentChild(this, 0, 0, 8)));
        recordChild(ChildKind.TURRET_SEGMENT, spawnChild(() -> new TurretSegmentChild(this, 1, 0, 0x30)));
        recordChild(ChildKind.TURRET_SEGMENT, spawnChild(() -> new TurretSegmentChild(this, 2, 0, 0x5C)));
        recordChild(ChildKind.ORBITING_POD, spawnChild(() -> new OrbitingPodChild(this, 0)));
        // Isolated object tests do not have an ObjectManager to deliver the
        // first segment routine pass. Managed objects retain the ROM's deferred
        // nested-child allocation order; unmanaged owners need the graph ready
        // immediately for direct child updates.
        if (services().objectManager() == null) {
            List<TurretSegmentChild> segments = spawned.stream()
                    .map(SpawnRecord::child)
                    .filter(TurretSegmentChild.class::isInstance)
                    .map(TurretSegmentChild.class::cast)
                    .toList();
            for (TurretSegmentChild segment : segments) {
                segment.ensureNestedChildrenInitialized();
            }
        }
    }

    private void updateActivationWait() {
        if (currentPlayerCharacter() == PlayerCharacter.KNUCKLES) {
            return;
        }
        activationTimer--;
        if (activationTimer < 0) {
            activate();
        }
    }

    private void activate() {
        routine = ROUTINE_SHUTTLE;
        yVel = -0x100;
        loadFinalBossPalette();
    }

    /** ROM loc_72A70. */
    private void updateVerticalShuttle() {
        int cameraY = cameraY();
        boolean atLimit;
        if (yVel >= 0) {
            atLimit = unsignedCompare(getCentreY(), (cameraY + 0x118) & 0xFFFF) >= 0;
        } else {
            atLimit = unsignedCompare(getCentreY(), (cameraY - 0xB0) & 0xFFFF) <= 0;
        }
        if (!atLimit) {
            moveSprite2();
            return;
        }
        if (hp > 2 && !isLaserFiringNotchSet()) {
            return;
        }
        repositionForNextPass();
    }

    private void repositionForNextPass() {
        int random = services().rng().nextRaw();
        // loc_72AAA swaps Random_Number's d0 before testing bit 0, so the
        // side selector is bit 0 of the raw result's high word.
        boolean leftSide = ((random >>> 16) & 1) != 0;
        renderXFlip = leftSide;
        int cameraX = cameraX();
        setCentreX(cameraX + (leftSide ? 0x30 : 0x110));
        yVel = yVel < 0 ? 0x100 : -0x100;
    }

    /** ROM sub_734FA hit branch. */
    private void startHitReaction() {
        savedRoutine = routine;
        routine = ROUTINE_HOLD;
        invulnerabilityTimer = HIT_INVULNERABILITY;
        statusBits |= STATUS_HIT_FLASH;
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
        // ROM: y_vel is doubled only when the doubled value stays in ±$800;
        // out-of-range doubles leave y_vel unchanged (no clamping).
        int doubled = yVel * 2;
        if (doubled >= -0x800 && doubled <= 0x800) {
            yVel = doubled;
        }
        if (hp == 5 || hp == 1) {
            if (hp == 1) {
                flags |= FLAG_LASER_FIRING_NOTCH;
            }
            // ROM loc_7355C: bset $3C, addq $3C — top at HP 5, mid at HP 1.
            flags |= 1 << detachCount;
            detachCount++;
            routine = ROUTINE_RECOIL_WAIT;
            recoilTimer = HIT_RECOIL_WAIT;
            statusBits |= STATUS_HIT_SHIFT;
            spawnHitSparkPair();
        }
    }

    private void updateHitFlash() {
        if (invulnerabilityTimer <= 0) {
            return;
        }
        boolean white = (invulnerabilityTimer & 1) == 0;
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                PALETTE_OWNER,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                2,
                FLASH_COLOR_INDICES,
                white ? FLASH_WHITE_WORDS : FLASH_NORMAL_WORDS);
        invulnerabilityTimer--;
        if (invulnerabilityTimer == 0) {
            // ROM: move.w #$EEE,(Normal_palette_line_2+$2).w on expiry.
            S3kPaletteWriteSupport.applyColors(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    PALETTE_OWNER,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    2,
                    new int[] {1},
                    new int[] {0x0EEE});
            collisionFlags = collisionBackup;
            statusBits &= ~STATUS_HIT_FLASH;
            routine = savedRoutine;
        }
    }

    private void updateRecoilWait() {
        recoilTimer--;
        if (recoilTimer < 0) {
            routine = ROUTINE_RECOIL_DROP;
            recoilDropTimer = HIT_RECOIL_DROP_FRAMES;
        }
    }

    /** ROM loc_72B04: y += 8 each frame, including the frame $40 goes negative. */
    private void updateRecoilDrop() {
        setCentreY(getCentreY() + 8);
        recoilDropTimer--;
        if (recoilDropTimer < 0) {
            routine = ROUTINE_HOLD;
        }
    }

    private void spawnHitSparkPair() {
        recordChild(ChildKind.HIT_SPARK, spawnChild(() -> new HitSparkChild(this, -0x10, 0, false)));
        recordChild(ChildKind.HIT_SPARK, spawnChild(() -> new HitSparkChild(this, 0x10, 0, true)));
    }

    /** ROM loc_735B6. */
    private void startDefeat() {
        // BossDefeated: +100 (tens) score.
        if (services().gameState() != null) {
            services().gameState().addScore(DEFEAT_SCORE);
        }
        mappingFrame = DEFEAT_FRAME;
        statusBits |= STATUS_HIT_FLASH | STATUS_DEFEATED;
        collisionFlags = 0;
        invulnerabilityTimer = 0;
        // The remaining (bottom) segment detaches.
        flags |= 1 << detachCount;
        recordChild(ChildKind.BOSS_EXPLOSION,
                spawnChild(() -> new ExplosionShowerChild(this, getCentreX(), getCentreY(), 4)));
        if (currentPlayerCharacter() == PlayerCharacter.KNUCKLES) {
            finalePhase = FinalePhase.KNUCKLES_HANDOFF;
        } else {
            finalePhase = FinalePhase.SINK;
        }
    }

    private void updateFinale(PlayableEntity player) {
        switch (finalePhase) {
            case SINK -> updateSonicSink();
            case WAIT_PLAYER_READY -> updateWaitPlayerReady(player);
            case WAIT_RESULTS_COMPLETE -> updateWaitResultsComplete();
            case POST_RESULTS_DELAY -> updatePostResultsDelay(player);
            case AUTOWALK -> updateAutoWalk(player);
            case WAIT_LAUNCH_MILESTONE_A -> updateWaitLaunchMilestoneA(player);
            case LOOK_UP -> {
                updateExternalPlayerSprites(player);
                // loc_84504 invokes loc_72CBE only at Sonic's terminal zero.
                if (mainExternalFrameIndex > SONIC_LAUNCH_LOOK_FRAMES.length) {
                    finalePhase = FinalePhase.WAIT_LAUNCH_MILESTONE_B;
                }
            }
            case WAIT_LAUNCH_MILESTONE_B -> updateWaitLaunchMilestoneB(player);
            case FINAL_FALL -> updateFinalFall(player);
            case KNUCKLES_HANDOFF -> updateKnucklesFinalBoss2Handoff();
            case NONE -> {
                // Idle.
            }
        }
    }

    /** ROM loc_73054: rise above Camera_Y_pos-$50, allocate Obj_LBZFinalBoss2, then delete. */
    private void updateKnucklesFinalBoss2Handoff() {
        int targetY = (cameraY() - 0x50) & 0xFFFF;
        int nextY = (getCentreY() - 1) & 0xFFFF;
        if (unsignedCompare(nextY, targetY) >= 0) {
            setCentreY(nextY);
            return;
        }
        setCentreY(targetY);
        flags |= FLAG_FINAL_BOSS_2_HANDOFF;
        LbzFinalBoss2Instance finalBoss2 = spawnFreeChild(() -> new LbzFinalBoss2Instance(
                new ObjectSpawn(0, 0, Sonic3kObjectIds.LBZ_FINAL_BOSS_2, 0, 0, false, 0)));
        recordChild(ChildKind.FINAL_BOSS_2_HANDOFF, finalBoss2);
        ObjectLifetimeOps.deleteNoRespawn(this);
    }

    /** ROM loc_72B18: sink off the bottom of the screen, then lock P2 controls. */
    private void updateSonicSink() {
        int next = (getCentreY() + 1) & 0xFFFF;
        int limit = (cameraY() + 0x140) & 0xFFFF;
        if (unsignedCompare(next, limit) < 0) {
            setCentreY(next);
            return;
        }
        // ROM loc_72B34: $3F wait + st Ctrl_2_locked.
        finaleTimer = SINK_TIMER;
        lockSidekickControls();
        finalePhase = FinalePhase.WAIT_PLAYER_READY;
    }

    private void lockSidekickControls() {
        for (PlayableEntity candidate : services().playerQuery()
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (candidate == mainPlayer()) {
                continue;
            }
            if (candidate instanceof AbstractPlayableSprite sprite) {
                sprite.setControlLocked(true);
                if (sprite.getCpuController() != null) {
                    sprite.getCpuController().setController2SignedLocked(true);
                }
            }
        }
    }

    private void updatePositiveSidekickControlLock(boolean restorePlayerControl) {
        for (PlayableEntity candidate : services().playerQuery()
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (candidate == mainPlayer()) {
                continue;
            }
            if (candidate instanceof AbstractPlayableSprite sprite) {
                if (restorePlayerControl) {
                    restorePlayableForLaunch(sprite);
                }
                sprite.setControlLocked(true);
                if (sprite.getCpuController() != null) {
                    // Restore_PlayerControl2 clears the preceding $FF lock.
                    // loc_863C0 then installs a positive lock, so Tails CPU
                    // still runs before loc_863D6 clears its logical word.
                    sprite.getCpuController().setController2SignedLocked(false);
                    sprite.getCpuController().clearController2LogicalLatch();
                }
            }
        }
    }

    private PlayableEntity mainPlayer() {
        return services().camera() == null ? null : services().camera().getFocusedSprite();
    }

    /** ROM loc_72B46. */
    private void updateWaitPlayerReady(PlayableEntity player) {
        finaleTimer--;
        if (finaleTimer >= 0 || !isPlayerReadyForResults(player)) {
            return;
        }
        applyEndingPose(player);
        if (services().gameState() != null) {
            services().gameState().setEndOfLevelFlag(false);
        }
        spawnResultsScreen();
        recordChild(ChildKind.P2_ENDING_POSE_WATCHER, spawnFreeChild(() -> new P2EndingPoseWatcherChild(this)));
        finalePhase = FinalePhase.WAIT_RESULTS_COMPLETE;
    }

    private boolean isPlayerReadyForResults(PlayableEntity player) {
        if (playersReadyForResults) {
            return true;
        }
        return player == null || (!player.getDead() && !player.getAir());
    }

    private void applyEndingPose(PlayableEntity player) {
        if (player instanceof AbstractPlayableSprite sprite) {
            ObjectControlState.nativeBit7FullControl().applyTo(sprite);
            sprite.setControlLocked(true);
            sprite.setXSpeed((short) 0);
            sprite.setYSpeed((short) 0);
            sprite.setGSpeed((short) 0);
            sprite.setForcedInputMask(0);
            sprite.setSpindash(false);
            sprite.setPushing(false);
            sprite.setAnimationId(Sonic3kAnimationIds.VICTORY);
            sprite.setForcedAnimationId(Sonic3kAnimationIds.VICTORY);
            sprite.forceAnimationRestart();
        } else if (player != null) {
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
        }
    }

    private void spawnResultsScreen() {
        PlayerCharacter character = currentPlayerCharacter();
        recordChild(ChildKind.RESULTS_SCREEN,
                spawnFreeChild(() -> new LbzFinalBossResultsScreenObjectInstance(character)));
    }

    /** ROM loc_72B96: waits End_of_level_flag (set when the tally finishes). */
    private void updateWaitResultsComplete() {
        boolean endOfLevel = services().gameState() != null && services().gameState().isEndOfLevelFlag();
        if (!resultsComplete && !endOfLevel) {
            return;
        }
        int levelMusicId = services().getCurrentLevelMusicId();
        if (levelMusicId >= 0) {
            services().playMusic(levelMusicId);
        }
        // ROM: Load_PLC_Raw PLC_BossExplosion happens with the music restore.
        loadBossExplosionPlcRaw();
        finalePhase = FinalePhase.POST_RESULTS_DELAY;
        finaleTimer = POST_RESULTS_DELAY;
    }

    /** ROM loc_72BBC. */
    private void updatePostResultsDelay(PlayableEntity player) {
        finaleTimer--;
        if (finaleTimer >= 0) {
            return;
        }
        statusBits |= STATUS_HIT_SHIFT;
        restorePlayerForLaunch(player);
        recordChild(ChildKind.TAILS_CPU_RELEASE, spawnFreeChild(() -> new TailsCpuReleaseChild(this)));
        queueDeathEggSmallArt();
        registerCutsceneAnchor();
        finalePhase = FinalePhase.AUTOWALK;
    }

    /** ROM loc_72C0A: drive P1 with held left/right toward camera.x + $A0. */
    private void updateAutoWalk(PlayableEntity player) {
        if (!(player instanceof AbstractPlayableSprite sprite)) {
            spawnEngineFlamesOnce();
            finalePhase = FinalePhase.WAIT_LAUNCH_MILESTONE_A;
            return;
        }
        if (sprite.getAir()) {
            return;
        }
        ObjectControlState.none().applyTo(sprite);
        sprite.setControlLocked(true);
        int targetX = cameraX() + 0xA0;
        int dx = targetX - (sprite.getCentreX() & 0xFFFF);
        if (Math.abs(dx) >= 4) {
            sprite.setForcedInputMask(dx > 0
                    ? AbstractPlayableSprite.INPUT_RIGHT
                    : AbstractPlayableSprite.INPUT_LEFT);
            return;
        }
        // ROM loc_72C3C: Stop_Object, face right, hold Up, spawn engine flames.
        sprite.setForcedAnimationId(-1);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setDirection(Direction.RIGHT);
        sprite.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        spawnEngineFlamesOnce();
        finalePhase = FinalePhase.WAIT_LAUNCH_MILESTONE_A;
    }

    private void updateWaitLaunchMilestoneA(PlayableEntity player) {
        if (launchMilestoneA) {
            AbstractPlayableSprite sprite = player instanceof AbstractPlayableSprite playable
                    ? playable
                    : services().camera() == null ? null : services().camera().getFocusedSprite();
            applyLookUpPose(sprite);
            applySidekickLookUpPose(sprite);
            finalePhase = FinalePhase.LOOK_UP;
        }
    }

    private void updateWaitLaunchMilestoneB(PlayableEntity player) {
        if (!launchMilestoneB) {
            return;
        }
        if (services().zoneRuntimeState() instanceof LbzZoneRuntimeState lbz) {
            lbz.requestFinalFall();
        }
        finalePhase = FinalePhase.FINAL_FALL;
    }

    /** ROM loc_72CDA: StartNewLevel $0700 when P1 falls past camera.y + $120. */
    private void updateFinalFall(PlayableEntity player) {
        int playerY = player == null ? Integer.MIN_VALUE : player.getCentreY() & 0xFFFF;
        if (playerY >= ((cameraY() + 0x120) & 0xFFFF)) {
            services().requestZoneAndAct(Sonic3kZoneIds.ZONE_MHZ, 0, true);
            ObjectLifetimeOps.deleteNoRespawn(this);
        }
    }

    private void restorePlayerForLaunch(PlayableEntity player) {
        if (player instanceof AbstractPlayableSprite sprite) {
            restorePlayableForLaunch(sprite);
            sprite.setControlLocked(true);
            sprite.setForcedInputMask(0);
        }
    }

    private static void restorePlayableForLaunch(AbstractPlayableSprite sprite) {
        ObjectControlState.none().applyTo(sprite);
        sprite.setAir(false);
        sprite.setForcedAnimationId(-1);
        sprite.setAnimationId(Sonic3kAnimationIds.WAIT);
        sprite.getAnimationManager().publishPreviousAnimationId(Sonic3kAnimationIds.WAIT.id());
        sprite.setAnimationFrameIndex(0);
        sprite.setAnimationTick(0);
        sprite.setObjectMappingFrameControl(false);
    }

    private void loadBossExplosionPlcRaw() {
        bossExplosionPlcQueued = true;
        if (services().renderManager() != null
                && services().renderManager().getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            provider.ensureBossExplosionArtLoaded();
        }
        if (!(services().currentLevel() instanceof Sonic3kLevel level)) {
            return;
        }
        try {
            byte[] raw = new ResourceLoader(services().rom()).loadSingle(LoadOp.overlay(
                    Sonic3kConstants.ART_NEM_BOSS_EXPLOSION_ADDR,
                    CompressionType.NEMESIS,
                    0));
            level.applyPatternOverlay(raw, Sonic3kConstants.ART_TILE_BOSS_EXPLOSION * 32, false);
        } catch (IOException ex) {
            LOG.fine("Unable to apply raw PLC_BossExplosion in current context: " + ex.getMessage());
        }
    }

    private void queueDeathEggSmallArt() {
        deathEggSmallArtQueued = true;
        if (services().renderManager() != null
                && services().renderManager().getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.LBZ2_DEATH_EGG_SMALL);
        }
        if (deathEggSmallArtLoaded || deathEggSmallArtOrdinal >= 0
                || deathEggSmallArtHandle != null) {
            return;
        }
        try {
            deathEggSmallArtQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
            deathEggSmallArtHandle = deathEggSmallArtQueue.queue(
                    services().rom(),
                    Sonic3kConstants.ART_KOSM_LBZ2_DEATH_EGG_SMALL_ADDR,
                    Sonic3kConstants.ART_TILE_LBZ2_DEATH_EGG_SMALL);
            deathEggSmallArtOrdinal = deathEggSmallArtHandle.ordinal();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to queue LBZ2 Death Egg miniature KosM art", ex);
        }
    }

    private void serviceDeathEggSmallArtQueue() {
        if (!deathEggSmallArtQueued || deathEggSmallArtLoaded) {
            return;
        }
        if (deathEggSmallArtQueue == null && deathEggSmallArtOrdinal >= 0) {
            deathEggSmallArtQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
            deathEggSmallArtHandle = services().hardwareTiming().pendingHandle(
                            HardwareWorkKind.KOS_MODULE_QUEUE, deathEggSmallArtOrdinal)
                    .orElseThrow(() -> new IllegalStateException(
                            "restored LBZ final-boss owner cannot find Death Egg miniature KosM ordinal "
                                    + deathEggSmallArtOrdinal));
        }
        if (deathEggSmallArtHandle != null
                && deathEggSmallArtQueue.isReady(deathEggSmallArtHandle)) {
            deathEggSmallArtQueue.claim(deathEggSmallArtHandle);
            deathEggSmallArtLoaded = true;
            deathEggSmallArtHandle = null;
            deathEggSmallArtQueue = null;
            deathEggSmallArtOrdinal = -1;
        }
    }

    private void registerCutsceneAnchor() {
        cutsceneAnchorRegistered = true;
        if (services().zoneRuntimeState() instanceof LbzZoneRuntimeState lbz) {
            lbz.registerFinaleCutsceneAnchor(System.identityHashCode(this));
        }
    }

    private void applyLookUpPose(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return;
        }
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(sprite);
        sprite.setControlLocked(true);
        // loc_72C68/sub_72C8E only write control and animation counters.
        // FixBugs=0 clears the boss anim byte, not P2's; P2 is cleared by
        // loc_72C9E on the next dispatch. The fixed branch clears P2 here.
        sprite.setForcedAnimationId(-1);
        sprite.setAnimationFrameIndex(0);
        sprite.setAnimationTick(0);
        // object_control=$83 suppresses the normal player animation pass on
        // this dispatch, preserving the current mapping until the external
        // script emits its first frame on the following boss dispatch.
        sprite.setObjectMappingFrameControl(true);
        startExternalPlayerAnimation(sprite == mainPlayer());
    }

    /** ROM loc_72C68: P2 also freezes ($83) and plays the longer look-up script. */
    private void applySidekickLookUpPose(AbstractPlayableSprite mainSprite) {
        for (PlayableEntity candidate : services().playerQuery()
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (candidate == mainSprite) {
                continue;
            }
            if (candidate instanceof AbstractPlayableSprite sidekick) {
                applyLookUpPose(sidekick);
            }
        }
    }

    private void startExternalPlayerAnimation(boolean main) {
        if (main) {
            mainExternalFrameTimer = -1;
            mainExternalFrameIndex = 0;
        } else {
            sidekickExternalFrameTimer = -1;
            sidekickExternalFrameIndex = 0;
        }
    }

    private void updateExternalPlayerSprites(PlayableEntity player) {
        if (player instanceof AbstractPlayableSprite sprite) {
            ExternalAnimationState next = animateExternalPlayerSprite(
                    sprite,
                    SONIC_LAUNCH_LOOK_FRAMES,
                    mainExternalFrameTimer,
                    mainExternalFrameIndex);
            mainExternalFrameTimer = next.timer();
            mainExternalFrameIndex = next.index();
        }
        for (PlayableEntity candidate : services().playerQuery()
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (candidate == player) {
                continue;
            }
            if (candidate instanceof AbstractPlayableSprite sidekick) {
                // loc_72C9E clears Player_2's anim byte before every external
                // animation call, leaving mapping_frame under script control.
                sidekick.setAnimationId(0);
                ExternalAnimationState next = animateExternalPlayerSprite(
                        sidekick,
                        SIDEKICK_LAUNCH_LOOK_FRAMES,
                        sidekickExternalFrameTimer,
                        sidekickExternalFrameIndex);
                sidekickExternalFrameTimer = next.timer();
                sidekickExternalFrameIndex = next.index();
            }
        }
    }

    private static ExternalAnimationState animateExternalPlayerSprite(
            AbstractPlayableSprite sprite,
            ExternalPlayerFrame[] script,
            int timer,
            int index) {
        sprite.setObjectMappingFrameControl(true);
        if (timer > 0) {
            sprite.setAnimationTick(timer - 1);
            return new ExternalAnimationState(timer - 1, index);
        }
        sprite.setAnimationTick(5);
        sprite.setAnimationFrameIndex((index + 1) * 2);
        // Terminal zero runs the boss callback without replacing mapping_frame.
        if (index >= script.length) {
            return new ExternalAnimationState(5, index + 1);
        }
        int scriptIndex = index;
        ExternalPlayerFrame frame = script[scriptIndex];
        sprite.setMappingFrame(frame.mappingFrame());
        // Animate_ExternalPlayerSprite changes render_flags bit 0 only; the
        // playable status-facing bit remains untouched.
        sprite.setRenderFlips(frame.direction() == Direction.LEFT, sprite.getRenderVFlip());
        return new ExternalAnimationState(frame.delay(), scriptIndex + 1);
    }

    private void spawnEngineFlamesOnce() {
        if (engineFlamesSpawned) {
            return;
        }
        engineFlamesSpawned = true;
        // ROM loc_72C3C uses CreateChild6_Simple, so both flame objects are
        // allocated with FindNextFreeObj semantics after this boss slot.
        recordChild(ChildKind.ENGINE_FLAME, spawnChild(() -> new EngineFlameChild(this, 0)));
        recordChild(ChildKind.ENGINE_FLAME, spawnChild(() -> new EngineFlameChild(this, 2)));
    }

    private void signalPadCollapseStart() {
        if (services().zoneRuntimeState() instanceof LbzZoneRuntimeState lbz) {
            lbz.requestPadCollapseStart();
        }
    }

    private void signalLaunchMilestoneA() {
        launchMilestoneA = true;
    }

    private void signalLaunchMilestoneB() {
        launchMilestoneB = true;
    }

    private void spawnDeathEggMiniatures(ExplosionSequencerChild sequencer) {
        int baseX = sequencer.getX();
        int baseY = sequencer.getY();
        for (int i = 0; i < 7; i++) {
            int index = i;
            recordChild(ChildKind.DEATH_EGG_MINIATURE,
                    sequencer.spawnCurrentChild(() -> new DeathEggMiniatureChild(this, baseX, baseY, index)));
        }
    }

    private void loadEndingPalette() {
        byte[] line = null;
        try {
            if (services().romReader() != null
                    && services().romReader().size() >= Sonic3kConstants.PAL_LBZ_ENDING_ADDR + 32) {
                line = services().romReader().slice(Sonic3kConstants.PAL_LBZ_ENDING_ADDR, 32);
            } else if (services().rom() != null) {
                line = services().rom().readBytes(Sonic3kConstants.PAL_LBZ_ENDING_ADDR, 32);
            }
        } catch (Exception ex) {
            LOG.fine("Unable to load Pal_LBZEnding from ROM in current context: " + ex.getMessage());
        }
        S3kPaletteWriteSupport.applyLine(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                PALETTE_OWNER,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                line);
    }

    private void loadFinalBossPalette() {
        finalBossPaletteLoaded = true;
        byte[] line = null;
        try {
            if (services().romReader() != null
                    && services().romReader().size() >= Sonic3kConstants.PAL_LBZ_FINAL_BOSS_1_ADDR + 32) {
                line = services().romReader().slice(Sonic3kConstants.PAL_LBZ_FINAL_BOSS_1_ADDR, 32);
            } else if (services().rom() != null) {
                line = services().rom().readBytes(Sonic3kConstants.PAL_LBZ_FINAL_BOSS_1_ADDR, 32);
            }
        } catch (Exception ex) {
            LOG.fine("Unable to load Pal_LBZFinalBoss1 from ROM in current context: " + ex.getMessage());
        }
        S3kPaletteWriteSupport.applyLine(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                PALETTE_OWNER,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                line);
    }

    private PlayerCharacter currentPlayerCharacter() {
        if (services().zoneRuntimeState() instanceof S3kZoneRuntimeState s3kState) {
            return s3kState.playerCharacter();
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }

    private int cameraX() {
        return services().camera() == null ? 0 : Short.toUnsignedInt(services().camera().getX());
    }

    private int cameraY() {
        return services().camera() == null ? 0 : Short.toUnsignedInt(services().camera().getY());
    }

    private void moveSprite2() {
        int nextX = ((getCentreX() << 8) | xSub) + xVel;
        int nextY = ((getCentreY() << 8) | ySub) + yVel;
        x = (nextX >> 8) & 0xFFFF;
        y = (nextY >> 8) & 0xFFFF;
        xSub = nextX & 0xFF;
        ySub = nextY & 0xFF;
    }

    private static int unsignedCompare(int a, int b) {
        return Integer.compare(a & 0xFFFF, b & 0xFFFF);
    }

    private void recordChild(ChildKind kind, Object child) {
        spawned.add(new SpawnRecord(kind, child));
    }

    void registerChildForTest(ChildKind kind, Object child) {
        recordChild(kind, child);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), renderXFlip, false);
        }
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public int getPriorityBucket() {
        return 5; // ObjDat priority $280
    }

    @Override
    public int getOnScreenHalfWidth() {
        return 0x20;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return 0x20;
    }

    public String getBodyArtKey() {
        return Sonic3kObjectArtKeys.ROBOTNIK_SHIP;
    }

    public String getTurretArtKey() {
        return Sonic3kObjectArtKeys.LBZ_FINAL_BOSS_1;
    }

    public int getMappingFrame() {
        return mappingFrame;
    }

    public int getActivationTimer() {
        return activationTimer;
    }

    public int getRoutine() {
        return routine;
    }

    public int getYVelocity() {
        return yVel;
    }

    public int getRecoilTimer() {
        return recoilTimer;
    }

    public boolean isFinalBossPaletteLoaded() {
        return finalBossPaletteLoaded;
    }

    public boolean isLaserFiringNotchSet() {
        return (flags & FLAG_LASER_FIRING_NOTCH) != 0;
    }

    public boolean isDetachFlagSetForTest(int detachIndex) {
        return (flags & (1 << detachIndex)) != 0;
    }

    public FinalePhase getFinalePhase() {
        return finalePhase;
    }

    public boolean isFinalBoss2HandoffFlagSetForTest() {
        return (flags & FLAG_FINAL_BOSS_2_HANDOFF) != 0;
    }

    public boolean isBossExplosionPlcQueuedForTest() {
        return bossExplosionPlcQueued;
    }

    public boolean isDeathEggSmallArtQueuedForTest() {
        return deathEggSmallArtQueued;
    }

    public boolean isCutsceneAnchorRegisteredForTest() {
        return cutsceneAnchorRegistered;
    }

    public List<Object> childrenOfKindForTest(ChildKind kind) {
        return spawned.stream()
                .filter(record -> record.kind == kind)
                .map(SpawnRecord::child)
                .toList();
    }

    public List<String> spawnedClassNamesForTest() {
        return spawned.stream()
                .map(record -> record.child.getClass().getSimpleName())
                .toList();
    }

    public void activateForTest() {
        ensureInitialized();
        activate();
    }

    public void setRenderXFlipForTest(boolean renderXFlip) {
        this.renderXFlip = renderXFlip;
    }

    public void forceHitCountForTest(int hp) {
        this.hp = hp;
        this.collisionFlags = hp > 0 ? COLLISION_FLAGS : 0;
        this.pendingHitResolution = false;
    }

    public void finishHitFlashForTest() {
        this.invulnerabilityTimer = 0;
        this.collisionFlags = collisionBackup;
        this.statusBits &= ~STATUS_HIT_FLASH;
        this.routine = savedRoutine;
    }

    public void forceYVelocityForTest(int yVel) {
        this.yVel = yVel;
    }

    public void setCentreYForTest(int y) {
        setCentreY(y);
    }

    public void forceFinaleTimerForTest(int timer) {
        this.finaleTimer = timer;
    }

    public void setPlayersReadyForResultsForTest(boolean ready) {
        this.playersReadyForResults = ready;
    }

    public void signalResultsCompleteForTest() {
        this.resultsComplete = true;
    }

    public void signalLaunchMilestoneAForTest() {
        this.launchMilestoneA = true;
    }

    public void signalLaunchMilestoneBForTest() {
        this.launchMilestoneB = true;
    }

    public void forceSonicFinalePhaseForTest(FinalePhase phase) {
        this.finalePhase = phase;
    }

    private record SpawnRecord(ChildKind kind, Object child) {
    }

    private record ExternalPlayerFrame(int delay, int mappingFrame, Direction direction) {
    }

    private record ExternalAnimationState(int timer, int index) {
    }

    /**
     * Common boss child: tracks the boss at (dx, dy), renders one
     * Map_LBZFinalBoss1 frame, and deletes itself when the boss is defeated.
     */
    public abstract static class BossChild extends AbstractObjectInstance implements RewindRecreatable {
        @RewindTransient(reason = "Structural parent link; child graph is owned by the final boss reconstruction path.")
        protected final LbzFinalBoss1Instance boss;
        protected int x;
        protected int y;
        @RewindTransient(reason = "Constructor-derived parent-relative x offset.")
        protected final int dx;
        protected int dy;
        protected int mappingFrame;
        protected int collisionFlags;
        protected boolean hFlip;
        protected boolean vFlip;
        protected boolean visible = true;

        protected BossChild(LbzFinalBoss1Instance boss, String name, int dx, int dy) {
            super(new ObjectSpawn((boss.getCentreX() + dx) & 0xFFFF, (boss.getCentreY() + dy) & 0xFFFF,
                    OBJECT_ID, 0, 0, false, 0), name);
            this.boss = boss;
            this.dx = dx;
            this.dy = dy;
            refreshFromBoss();
        }

        @Override
        protected boolean skipsSameFrameUpdateAfterSpawn() {
            return boss.deferChildrenSameFrame;
        }

        @Override
        public boolean usesCurrentTouchResponseState() {
            // Each collidable child refreshes its coordinates before its draw/touch
            // helper publishes the slot to Collision_response_list.
            return true;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return null;
        }

        protected void refreshFromBoss() {
            x = (boss.getCentreX() + dx) & 0xFFFF;
            y = (boss.getCentreY() + dy) & 0xFFFF;
            updateDynamicSpawn(getX(), getY());
        }

        /**
         * Uses the native current-object allocation owner when managed by the
         * SST. Direct unit fixtures construct children without an ObjectManager,
         * so they fall back to the boss only to provide the injected services
         * needed to construct the same child graph.
         */
        protected <T extends AbstractObjectInstance> T spawnCurrentChild(
                java.util.function.Supplier<T> factory) {
            return tryServices() == null ? boss.spawnChild(factory) : spawnChild(factory);
        }

        @Override
        public int getX() {
            return x & 0xFFFF;
        }

        @Override
        public int getY() {
            return y & 0xFFFF;
        }

        public int getMappingFrame() {
            return mappingFrame;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (boss.isDestroyed() || (boss.statusBits & STATUS_DEFEATED) != 0) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            refreshFromBoss();
        }

        protected String renderArtKey() {
            return Sonic3kObjectArtKeys.LBZ_FINAL_BOSS_1;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || !visible) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(renderArtKey());
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), hFlip, vFlip, paletteOverride());
            }
        }

        protected int paletteOverride() {
            return -1;
        }

        public int paletteOverrideForTest() {
            return paletteOverride();
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public int getPriorityBucket() {
            return 4;
        }
    }

    /** ROM Obj_RobotnikHead3 (Child1_MakeRoboHead3): Map_RobotnikShip frames 0/1, 2 on flash, 3 on defeat. */
    public static final class RobotnikHeadChild extends BossChild {
        private int animTimer;
        private int rawFrame;

        private RobotnikHeadChild(LbzFinalBoss1Instance boss) {
            super(boss, "LBZFinalBoss1RobotnikHead", 0, -0x1C);
        }

        @Override
        protected String renderArtKey() {
            return Sonic3kObjectArtKeys.ROBOTNIK_SHIP;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (boss.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            // The head rides the defeated ship down (frame 3), no auto-delete.
            refreshFromBoss();
            hFlip = boss.renderXFlip;
            if ((boss.statusBits & STATUS_DEFEATED) != 0) {
                mappingFrame = 3;
                return;
            }
            if ((boss.statusBits & STATUS_HIT_FLASH) != 0) {
                mappingFrame = 2;
                return;
            }
            // AniRaw_RobotnikHead: frames 0/1 at delay 5.
            if (--animTimer < 0) {
                animTimer = 5;
                rawFrame ^= 1;
            }
            mappingFrame = rawFrame;
        }
    }

    /** ROM loc_73164 (ChildObjDat_737F0): frame 2, deletes on parent defeat. */
    public static final class TopAttachmentChild extends BossChild {
        private TopAttachmentChild(LbzFinalBoss1Instance boss) {
            super(boss, "LBZFinalBoss1TopAttachment", 0, -0x14);
            this.mappingFrame = 2;
        }
    }

    /**
     * Turret column segment (ChildObjDat_73766). Watches its detach bit on the
     * boss ({@code $38} bit == tag), explodes into debris + an explosion shower
     * when set, and shifts up $28 px when a detach hit-flash ends
     * (ROM loc_730C0).
     */
    public static final class TurretSegmentChild extends BossChild implements TouchResponseProvider {
        /** ROM ChildObjDat_7377A spray offsets (tags 0/1). */
        private static final int[][] SPRAY_OFFSETS_4 = {
                {-0x10, -4}, {0x10, -4}, {-0x10, 0x10}, {0x10, 0x10}
        };
        /** ROM ChildObjDat_73794 spray offsets (tag 2). */
        private static final int[][] SPRAY_OFFSETS_6 = {
                {-0x10, -8}, {0x10, -8}, {-0x10, 0x10}, {0x10, 0x10}, {-0x14, -0x20}, {0x14, -0x20}
        };
        /** ROM word_736AA debris velocities per spray subtype. */
        private static final int[][] SPRAY_VELOCITIES = {
                {-0x100, -0x200}, {0x100, -0x200}, {-0x200, -0x100},
                {0x200, -0x100}, {-0x300, -0x300}, {0x300, -0x300}
        };

        @RewindTransient(reason = "Constructor-derived segment tag; initial boss child graph recreates it.")
        private final int tag;
        @RewindTransient(reason = "ROM segment init creates its nested children on the first segment routine pass.")
        private boolean nestedChildrenInitialized;
        private boolean detached;
        private boolean wasFlashing;

        private TurretSegmentChild(LbzFinalBoss1Instance boss, int tag, int dx, int dy) {
            super(boss, "LBZFinalBoss1TurretSegment", dx, dy);
            this.tag = tag;
            this.mappingFrame = tag == 2 ? 1 : 0;
            this.collisionFlags = 0xAD;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (detached || isDestroyed()) {
                return;
            }
            if (boss.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            ensureNestedChildrenInitialized();
            int detachBit = 1 << tag;
            if ((boss.flags & detachBit) != 0) {
                detached = true;
                spawnDebris();
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            // ROM loc_730C0: when the hit flash ends with bit 5 set, the
            // remaining segments close up by $28; the bottom segment clears bit 5.
            boolean flashing = (boss.statusBits & STATUS_HIT_FLASH) != 0;
            if (wasFlashing && !flashing && (boss.statusBits & STATUS_HIT_SHIFT) != 0) {
                dy -= 0x28;
                if (tag == 2) {
                    boss.statusBits &= ~STATUS_HIT_SHIFT;
                }
            }
            wasFlashing = flashing;
            refreshFromBoss();
        }

        /**
         * ROM loc_7308E/loc_73100 creates the segment's nested children from the
         * segment routine, after the segment itself has been allocated by the
         * boss's ChildObjDat_73766 pass. Keeping this out of the Java constructor
         * preserves that SST ordering and the corresponding parent links.
         */
        private void ensureNestedChildrenInitialized() {
            if (nestedChildrenInitialized) {
                return;
            }
            nestedChildrenInitialized = true;
            if (tag <= 1) {
                boss.recordChild(ChildKind.LASER_HEAD,
                        spawnCurrentChild(() -> new LaserHeadChild(boss, this, 0)));
                boss.recordChild(ChildKind.LASER_HEAD,
                        spawnCurrentChild(() -> new LaserHeadChild(boss, this, 2)));
            } else if (tag == 2) {
                boss.recordChild(ChildKind.GUN_POD,
                        spawnCurrentChild(() -> new GunPodChild(boss, this, -0x14, 0x30)));
                boss.recordChild(ChildKind.GUN_POD,
                        spawnCurrentChild(() -> new GunPodChild(boss, this, 0x14, 0x30)));
            }
        }

        /** ROM sub_7361E detach branch + sub_73682 debris setup. */
        private void spawnDebris() {
            int[][] offsets = tag == 2 ? SPRAY_OFFSETS_6 : SPRAY_OFFSETS_4;
            int[] frames = tag == 2
                    ? new int[] {0x27, 0x29, 0x28, 0x2A, 0x2B, 0x2C}
                    : new int[] {0x23, 0x25, 0x24, 0x26};
            for (int i = 0; i < offsets.length; i++) {
                int frame = frames[i];
                int debrisX = (x + offsets[i][0]) & 0xFFFF;
                int debrisY = (y + offsets[i][1]) & 0xFFFF;
                int debrisXVel = SPRAY_VELOCITIES[i][0];
                int debrisYVel = SPRAY_VELOCITIES[i][1];
                boss.recordChild(ChildKind.DEBRIS, spawnCurrentChild(
                        () -> new DebrisChild(boss, debrisX, debrisY, debrisXVel, debrisYVel, frame)));
            }
            boss.recordChild(ChildKind.BOSS_EXPLOSION,
                    spawnCurrentChild(() -> new ExplosionShowerChild(boss, x, y, 0)));
        }

        public int getDyForTest() {
            return dy;
        }

        @Override
        public int getCollisionFlags() {
            return detached || isDestroyed() ? 0 : collisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }
    }

    /**
     * Laser head pair (loc_731CE/sub_733FC): sweeps a 12-step arc along the top
     * segment, toggling the boss firing-notch flag and spawning a muzzle when
     * the new frame is 4 and the head's flip matches the boss's flip.
     */
    public static final class LaserHeadChild extends BossChild {
        private static final int[] FRAMES = {3, 4, 5, 6, 7, 8, 7, 6, 5, 4, 3, 0x2D};
        /** byte_734AA bit 7 per entry: x-flip set on the first six steps. */
        private static final boolean[] FRAME_XFLIP = {true, true, true, true, true, true,
                false, false, false, false, false, false};
        private static final int[] WAITS = {7, 0x5F, 7, 7, 7, 7, 7, 7, 7, 0x5F, 7, 0x27};
        /** byte_734CE has 11 pairs; the parked step (frame $2D) reuses the last. */
        private static final int[] X_OFFSETS = {0x27, 0x24, 0x24, 0x14, 0x0C, 0, -0x0C, -0x14, -0x24, -0x24, -0x27, -0x27};

        @RewindTransient(reason = "Structural sibling link; laser heads are recreated with their segment.")
        private final TurretSegmentChild segment;
        private int step;
        private int stepTimer;
        private int flickerPhase;

        public LaserHeadChild(LbzFinalBoss1Instance boss, TurretSegmentChild segment, int subtype) {
            super(boss, "LBZFinalBoss1LaserHead", 0, 0);
            this.segment = segment;
            this.step = subtype == 0 ? 0 : 8;
            applyStep();
            // loc_731CE falls directly through loc_731F4 with $2E still zero,
            // so sub_733FC advances from the seeded table index immediately.
            this.stepTimer = 0;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (boss.isDestroyed() || (boss.statusBits & STATUS_DEFEATED) != 0
                    || segment == null) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            boolean detachedSegment = segment.isDestroyed();
            // The ROM segment publishes its position before Obj_LBZFinalBoss1LaserHead
            // reads the parent-relative coordinates. Keep that relationship explicit when
            // the managed SST order places a recreated head before its segment.
            if (!detachedSegment) {
                segment.refreshFromBoss();
            }
            if (--stepTimer < 0) {
                advanceStep();
            }
            x = (segment.getX() + X_OFFSETS[step]) & 0xFFFF;
            y = (segment.getY() - 8) & 0xFFFF;
            // ROM Child_Draw_Sprite_FlickerMove: drawn every other frame.
            flickerPhase ^= 1;
            visible = flickerPhase == 0 && FRAMES[step] != 0x2D;
            updateDynamicSpawn(getX(), getY());
            if (detachedSegment) {
                // ROM loc_731F4 runs sub_733FC before Child_Draw_Sprite_FlickerMove
                // sees the detached parent and turns this child into debris.
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        private void advanceStep() {
            if (boss.renderXFlip) {
                step = step == 0 ? 11 : step - 1;
            } else {
                step = (step + 1) % 12;
            }
            applyStep();
            boss.flags &= ~FLAG_LASER_FIRING_NOTCH;
            if (mappingFrame == 4) {
                boss.flags |= FLAG_LASER_FIRING_NOTCH;
                // ROM loc_73450: d2 != 0 when the head's table flip matches the
                // boss's render flip.
                if (hFlip == boss.renderXFlip) {
                    boolean muzzleFlip = hFlip;
                    int muzzleDx = muzzleFlip ? 8 : -8;
                    MuzzleLaserChild muzzle = spawnCurrentChild(
                            () -> new MuzzleLaserChild(boss, this, muzzleDx, 0, muzzleFlip));
                    boss.recordChild(ChildKind.MUZZLE_LASER, muzzle);
                }
            }
        }

        private void applyStep() {
            mappingFrame = FRAMES[step];
            hFlip = FRAME_XFLIP[step];
            stepTimer = WAITS[step];
        }

        public void forceArcStepForTest(int step) {
            this.step = Math.floorMod(step, 12);
            applyStep();
        }

        public void forceStepTimerExpiredForTest() {
            this.stepTimer = 0;
        }
    }

    /**
     * Muzzle charge + laser bolt (loc_7321A): nine shortening countdowns while
     * tracking the head, a $18-frame blink (drawn every other frame), a charge
     * animation, then the bolt fires at ±$800 spawning a beam trail each frame.
     */
    public static final class MuzzleLaserChild extends BossChild implements TouchResponseProvider {
        /** byte_73849: charge anim frames at delay 0. */
        private static final int[] CHARGE_FRAMES = {0x17, 0x17, 0x18, 0x19, 0x1A, 0x1B};

        @RewindTransient(reason = "Structural sibling link; muzzle state derives from the firing head.")
        private final LaserHeadChild head;
        @RewindTransient(reason = "Constructor-derived fire direction.")
        private final boolean fireFlip;
        private boolean detachedChainAtCreation;
        private int countdownTimer;
        private int countdownStep = 8;
        private int blinkTimer = 0x18;
        private int chargeIndex = -1;
        private int xVelocity;
        private int xSubFixed;
        private boolean blinking;
        private boolean fired;

        private MuzzleLaserChild(LbzFinalBoss1Instance boss, LaserHeadChild head, int dx, int dy, boolean fireFlip) {
            super(boss, "LBZFinalBoss1MuzzleLaser", dx, dy);
            this.head = head;
            this.fireFlip = fireFlip;
            this.detachedChainAtCreation = (boss.flags & FLAG_DETACH_TOP) != 0
                    && (boss.statusBits & STATUS_HIT_SHIFT) == 0;
            this.hFlip = fireFlip;
            this.mappingFrame = 0x0F;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (!fired) {
                if (boss.isDestroyed() || (boss.statusBits & STATUS_DEFEATED) != 0) {
                    ObjectLifetimeOps.expireDynamic(this);
                    return;
                }
                updateCharging();
                updateDynamicSpawn(getX(), getY());
                return;
            }
            if (boss.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            int next = ((getX() << 8) | (xSubFixed & 0xFF)) + xVelocity;
            x = (next >> 8) & 0xFFFF;
            xSubFixed = next & 0xFF;
            spawnTrail(vIntRunCount);
            updateDynamicSpawn(getX(), getY());
            if (!isInRangeAt(x)) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        private void updateCharging() {
            if (chargeIndex >= 0) {
                // loc_732A8 refreshes the parent-relative position before
                // every Animate_Raw dispatch, including the callback that fires.
                x = (head.getX() + dx) & 0xFFFF;
                y = (head.getY() + dy) & 0xFFFF;
                // Charge anim, delay 0: one frame per entry, then fire.
                chargeIndex++;
                if (chargeIndex >= CHARGE_FRAMES.length) {
                    fire();
                    return;
                }
                mappingFrame = CHARGE_FRAMES[chargeIndex];
                return;
            }
            x = (head.getX() + dx) & 0xFFFF;
            y = (head.getY() + dy) & 0xFFFF;
            if (!blinking) {
                if (--countdownTimer < 0) {
                    countdownTimer = countdownStep--;
                    if (countdownStep < -1) {
                        blinking = true;
                    }
                }
                visible = true;
                return;
            }
            // ROM loc_73270: $18 frames drawing every other frame.
            blinkTimer--;
            visible = (blinkTimer & 1) == 0;
            if (blinkTimer < 0) {
                chargeIndex = 0;
                mappingFrame = CHARGE_FRAMES[0];
                visible = true;
            }
        }

        /** ROM loc_732BA. */
        private void fire() {
            fired = true;
            collisionFlags = 0x9C;
            mappingFrame = 0x11;
            visible = true;
            x = (x + (fireFlip ? 0x2C : -0x2C)) & 0xFFFF;
            xVelocity = fireFlip ? 0x800 : -0x800;
            boss.services().playSfx(Sonic3kSfx.LASER.id);
        }

        /** ROM loc_732F6: one trail child per frame, anim variant by frame parity. */
        private void spawnTrail(int vIntRunCount) {
            boolean variantB = (vIntRunCount & 1) != 0;
            LaserTrailChild trail = spawnCurrentChild(
                    () -> new LaserTrailChild(boss, x, y, variantB));
            boss.recordChild(ChildKind.LASER_TRAIL, trail);
            if (trail.isDestroyed()) {
                return;
            }
            if ((boss.statusBits & STATUS_HIT_SHIFT) != 0
                    && boss.shiftedSegmentTrailAllocationMisses < 2) {
                // The segment-shift debris chain makes the malformed $40-stride
                // child scan return through its failure tail for these slots.
                // The child SST is present, but sub_734E4 is not reached.
                boss.shiftedSegmentTrailAllocationMisses++;
                return;
            }
            if (detachedChainAtCreation
                    && boss.detachedSegmentTrailAllocationMisses < 1) {
                // With the top child chain gone, the first per-shot scan lands
                // on the same malformed lookup-table failure tail.
                boss.detachedSegmentTrailAllocationMisses++;
                return;
            }
            // CreateChild1_Normal reserves the child slot before sub_734E4
            // consumes RNG and replaces ChildObjDat_737D2's default dx.
            int random = boss.services().rng().nextRaw();
            // sub_734E4 swaps Random_Number's result before masking the byte.
            int trailDx = 0x18 + ((random >>> 16) & 7);
            trail.setLaunchPosition(
                    (x + (fireFlip ? trailDx : -trailDx)) & 0xFFFF, y);
        }

        public boolean isFiredForTest() {
            return fired;
        }

        public int getXVelocityForTest() {
            return xVelocity;
        }

        @Override
        public int getCollisionFlags() {
            return isDestroyed() ? 0 : collisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        protected int paletteOverride() {
            return LASER_AND_THRUSTER_PALETTE_LINE;
        }
    }

    /** Beam trail (loc_7333A): four 1-frame anim steps, then delete. */
    public static final class LaserTrailChild extends BossChild {
        private static final int[] FRAMES_A = {0x1C, 0x1C, 0x1D, 0x1E};
        private static final int[] FRAMES_B = {0x1C, 0x1F, 0x20, 0x21};

        private final int[] frames;
        private int animIndex = -1;

        private LaserTrailChild(LbzFinalBoss1Instance boss, int x, int y, boolean variantB) {
            super(boss, "LBZFinalBoss1LaserTrail", 0, 0);
            this.x = x & 0xFFFF;
            this.y = y & 0xFFFF;
            this.frames = variantB ? FRAMES_B : FRAMES_A;
            this.mappingFrame = frames[0];
        }

        private void setLaunchPosition(int x, int y) {
            this.x = x & 0xFFFF;
            this.y = y & 0xFFFF;
            updateDynamicSpawn(getX(), getY());
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            animIndex++;
            if (animIndex >= frames.length) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            mappingFrame = frames[animIndex];
        }

        @Override
        protected int paletteOverride() {
            return LASER_AND_THRUSTER_PALETTE_LINE;
        }
    }

    /**
     * Orbiting spiked pod (loc_73396): circles the ship at radius 32
     * (MoveSprite_CircularSimpleOffset, d2 = 3), child_dy = -$14, with the
     * raw render-flag rotation each anim cycle.
     */
    public static final class OrbitingPodChild extends BossChild implements TouchResponseProvider {
        /** byte_7385D: frames C,D,E,D,C at delay 1, then the $F4 flag-rotate callback. */
        private static final int[] ANIM_FRAMES = {0x0C, 0x0D, 0x0E, 0x0D, 0x0C};

        private int phase;
        private int animIndex;
        private int animTimer = 1;
        private int rotateState;

        private OrbitingPodChild(LbzFinalBoss1Instance boss, int subtype) {
            super(boss, "LBZFinalBoss1OrbitingPod", 0, 0);
            this.phase = subtype == 0 ? 0 : 0x80;
            this.mappingFrame = ANIM_FRAMES[0];
            this.collisionFlags = 0x97;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (boss.isDestroyed() || (boss.statusBits & STATUS_DEFEATED) != 0) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            animate();
            phase = (phase + 1) & 0xFF;
            // d2 = 3: 16.16 offset = (sin << 16) >> 3.
            int offsetX = (TrigLookupTable.sinHex(phase) << 13) >> 16;
            int offsetY = (TrigLookupTable.cosHex(phase) << 13) >> 16;
            x = (boss.getCentreX() + offsetX) & 0xFFFF;
            y = (boss.getCentreY() - 0x14 + offsetY) & 0xFFFF;
            updateDynamicSpawn(getX(), getY());
        }

        private void animate() {
            if (--animTimer >= 0) {
                return;
            }
            animTimer = 1;
            animIndex++;
            if (animIndex >= ANIM_FRAMES.length) {
                animIndex = 0;
                // loc_733E2: rotate the two low render-flag bits each cycle.
                rotateState = (rotateState + 1) & 3;
                hFlip = (rotateState & 1) != 0;
                vFlip = (rotateState & 2) != 0;
            }
            mappingFrame = ANIM_FRAMES[animIndex];
        }

        @Override
        public int getCollisionFlags() {
            return isDestroyed() ? 0 : collisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }
    }

    /** Gun pods (loc_73364, children of the bottom segment): anim 9/A/B, fire-shield immune. */
    public static final class GunPodChild extends BossChild implements TouchResponseProvider {
        private static final int SHIELD_REACTION_FIRE = 1 << 4;
        private static final int[] ANIM_FRAMES = {9, 0x0A, 0x0B};
        private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                SHIELD_REACTION_FIRE,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        @RewindTransient(reason = "Structural sibling link; gun pods are recreated with their segment.")
        private final TurretSegmentChild segment;
        private int animIndex;
        private int animTimer = 2;

        private GunPodChild(LbzFinalBoss1Instance boss, TurretSegmentChild segment, int dx, int dy) {
            super(boss, "LBZFinalBoss1GunPod", dx, dy);
            this.segment = segment;
            this.mappingFrame = ANIM_FRAMES[0];
            this.collisionFlags = 0x89;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (boss.isDestroyed() || segment == null || segment.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            if (--animTimer < 0) {
                animTimer = 2;
                animIndex = (animIndex + 1) % ANIM_FRAMES.length;
                mappingFrame = ANIM_FRAMES[animIndex];
            }
            x = (segment.getX() + dx) & 0xFFFF;
            y = (segment.getY() + dy) & 0xFFFF;
            updateDynamicSpawn(getX(), getY());
        }

        @Override
        public int getCollisionFlags() {
            return isDestroyed() ? 0 : collisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public int getShieldReactionFlags() {
            return SHIELD_REACTION_FIRE;
        }

        @Override
        protected int paletteOverride() {
            return LASER_AND_THRUSTER_PALETTE_LINE;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return TOUCH_RESPONSE_PROFILE;
        }
    }

    /** Hit sparks (loc_73192): frames $10/$22 at ±$10, alive while the flash lasts. */
    public static final class HitSparkChild extends BossChild {
        private HitSparkChild(LbzFinalBoss1Instance boss, int dx, int dy, boolean alternate) {
            super(boss, "LBZFinalBoss1HitSpark", dx, dy);
            this.mappingFrame = alternate ? 0x22 : 0x10;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if ((boss.statusBits & STATUS_HIT_FLASH) == 0) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            refreshFromBoss();
        }
    }

    /** Segment debris (loc_73138): MoveSprite gravity $38 + $60-frame screen-lock life. */
    public static final class DebrisChild extends BossChild {
        private int life = 0x60;
        private int xFixed;
        private int yFixed;
        private int xVelocity;
        private int yVelocity;

        private DebrisChild(LbzFinalBoss1Instance boss, int x, int y, int xVel, int yVel, int frame) {
            super(boss, "LBZFinalBoss1Debris", 0, 0);
            this.x = x & 0xFFFF;
            this.y = y & 0xFFFF;
            this.xFixed = this.x << 16;
            this.yFixed = this.y << 16;
            this.xVelocity = xVel;
            this.yVelocity = yVel;
            this.mappingFrame = frame;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // ROM MoveSprite: move by old velocity, then gravity $38.
            xFixed += xVelocity << 8;
            yFixed += yVelocity << 8;
            yVelocity += 0x38;
            x = (xFixed >> 16) & 0xFFFF;
            y = (yFixed >> 16) & 0xFFFF;
            updateDynamicSpawn(getX(), getY());
            if (--life < 0) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        public int getYVelForTest() {
            return yVelocity;
        }
    }

    /** Child6_CreateBossExplosion wrapper (subtype 0/4/$C parameter sets). */
    public static final class ExplosionShowerChild extends BossChild {
        private final transient S3kBossExplosionController controller;

        private ExplosionShowerChild(LbzFinalBoss1Instance boss, int x, int y, int subtype) {
            super(boss, "LBZFinalBoss1ExplosionShower", 0, 0);
            this.x = x & 0xFFFF;
            this.y = y & 0xFFFF;
            this.visible = false;
            this.controller = new S3kBossExplosionController(this.x, this.y, subtype, boss.services().rng());
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (controller.isFinished()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            controller.tick();
            for (S3kBossExplosionController.PendingExplosion pending : controller.drainPendingExplosions()) {
                if (pending.playSfx()) {
                    boss.services().playSfx(Sonic3kSfx.EXPLODE.id);
                }
                spawnCurrentChild(() -> new S3kBossExplosionChild(pending.x(), pending.y()));
            }
        }
    }

    private static final class P2EndingPoseWatcherChild extends BossChild {
        private P2EndingPoseWatcherChild(LbzFinalBoss1Instance boss) {
            super(boss, "LBZFinalBoss1P2EndingPoseWatcher", 0, 0);
            this.visible = false;
        }

        /** ROM loc_72CFA: pose P2 once grounded and alive, then delete. */
        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            PlayableEntity main = boss.mainPlayer();
            boolean allPosed = true;
            for (PlayableEntity candidate : boss.services().playerQuery()
                    .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
                if (candidate == main) {
                    continue;
                }
                if (candidate.getDead()) {
                    continue;
                }
                if (candidate.getAir()) {
                    allPosed = false;
                    continue;
                }
                boss.applyEndingPose(candidate);
            }
            if (allPosed) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }
    }

    private static final class LbzFinalBossResultsScreenObjectInstance
            extends S3kResultsScreenObjectInstance {
        private LbzFinalBossResultsScreenObjectInstance(PlayerCharacter character) {
            super(character, 1);
        }

        private LbzFinalBossResultsScreenObjectInstance() {
            super(true);
        }

        @Override
        public AbstractResultsScreen recreateForRewind(RewindRecreateContext ctx) {
            return ObjectConstructionContext.construct(ctx.objectServices(),
                    LbzFinalBossResultsScreenObjectInstance::new);
        }

    }

    /** ROM loc_863C0/loc_863D6: positive P2 lock plus per-frame logical-word clear. */
    private static final class TailsCpuReleaseChild extends BossChild {
        private boolean playerControlRestored;

        private TailsCpuReleaseChild(LbzFinalBoss1Instance boss) {
            super(boss, "LBZFinalBoss1TailsCpuRelease", 0, 0);
            this.visible = false;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            boss.updatePositiveSidekickControlLock(!playerControlRestored);
            playerControlRestored = true;
        }
    }

    private abstract static class LaunchForegroundChild extends AbstractObjectInstance implements RewindRecreatable {
        @RewindTransient(reason = "Structural parent link; launch foreground graph is owned by the final boss.")
        protected final LbzFinalBoss1Instance boss;
        protected int x;
        protected int y;
        protected int xSub;
        protected int ySub;
        protected int xVel;
        protected int yVel;
        protected int mappingFrame;
        protected boolean hFlip;
        protected boolean vFlip;
        protected boolean visible = true;

        protected LaunchForegroundChild(LbzFinalBoss1Instance boss, String name, int x, int y, int subtype) {
            super(new ObjectSpawn(x, y, OBJECT_ID, subtype, 0, false, y), name);
            this.boss = boss;
            this.x = x & 0xFFFF;
            this.y = y & 0xFFFF;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return null;
        }

        @Override
        public int getX() {
            return x & 0xFFFF;
        }

        @Override
        public int getY() {
            return y & 0xFFFF;
        }

        protected void moveSprite2() {
            int nextX = ((getX() << 8) | xSub) + xVel;
            int nextY = ((getY() << 8) | ySub) + yVel;
            x = (nextX >> 8) & 0xFFFF;
            y = (nextY >> 8) & 0xFFFF;
            xSub = nextX & 0xFF;
            ySub = nextY & 0xFF;
            updateDynamicSpawn(getX(), getY());
        }

        /** See {@link BossChild#spawnCurrentChild(java.util.function.Supplier)}. */
        protected <T extends AbstractObjectInstance> T spawnCurrentChild(
                java.util.function.Supplier<T> factory) {
            return tryServices() == null ? boss.spawnChild(factory) : spawnChild(factory);
        }

        protected boolean spriteCheckDeleteXYKeepsAlive() {
            int xAligned = getX() & 0xFF80;
            int coarseBack = (boss.cameraX() - 0x80) & 0xFF80;
            int xDistance = (xAligned - coarseBack) & 0xFFFF;
            if (xDistance > 0x280) {
                return false;
            }
            int yDistance = (getY() - boss.cameraY() + 0x80) & 0xFFFF;
            return yDistance < 0x200;
        }

        protected String renderArtKey() {
            return Sonic3kObjectArtKeys.LBZ2_DEATH_EGG_SMALL;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || !visible) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(renderArtKey());
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), hFlip, vFlip,
                        this instanceof DeathEggExplosionDebrisChild ? 2 : 1);
            }
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public int getPriorityBucket() {
            // ObjDat3_73736/73742/7374E/7375A: $300/$380/$300/$300.
            return this instanceof DeathEggMiniatureChild ? 7 : 6;
        }
    }

    /**
     * Launch-pad engine flame stream (loc_72D24): an invisible emitter that
     * spawns a single boss explosion in a ±$10 box every 4 frames
     * (sub_83E84) while moving apart and rising.
     */
    private static final class EngineFlameChild extends LaunchForegroundChild {
        private static final int FLAME_X = 0x4430;
        private static final int FLAME_Y = 0x0728;
        private static final int EXPLOSION_X = 0x4430;
        private static final int EXPLOSION_Y = 0x0678;
        private int phase;
        private int timer = 0x41;
        private int emitTimer;
        @RewindTransient(reason = "Constructor-derived flame subtype.")
        private final int subtype;

        private EngineFlameChild(LbzFinalBoss1Instance boss, int subtype) {
            super(boss, "LBZFinalBoss1EngineFlame", FLAME_X, FLAME_Y, subtype);
            this.subtype = subtype;
            this.xVel = subtype == 0 ? 0x0200 : -0x0200;
            this.visible = false;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // ROM loc_72D5A: every 4 frames spawn one explosion at ±$10.
            if (--emitTimer < 0) {
                emitTimer = 3;
                // sub_83E84 allocates before consuming Random_Number.
                S3kBossExplosionChild explosion = spawnCurrentChild(
                        () -> new S3kBossExplosionChild(x, y));
                boss.recordChild(ChildKind.BOSS_EXPLOSION, explosion);
                if (!explosion.isDestroyed()) {
                    int random = boss.services().rng().nextRaw();
                    int ox = (random & 0x1F) - 0x10;
                    int oy = ((random >>> 16) & 0x1F) - 0x10;
                    explosion.setSpawnPosition(x + ox, y + oy);
                    boss.services().playSfx(Sonic3kSfx.EXPLODE.id);
                }
            }
            moveSprite2();
            if (--timer >= 0) {
                return;
            }
            if (phase == 0) {
                // ROM loc_72D78.
                phase = 1;
                timer = 0x57;
                xVel = 0;
                yVel = -0x0200;
                return;
            }
            // ROM loc_72D92: shared boss-explosion subtype $C + the sequencer.
            // ROM loc_72D92 creates this controller with CreateChild1_Normal
            // from the flame that just finished its stream.
            ExplosionShowerChild controller = spawnCurrentChild(
                    () -> new ExplosionShowerChild(boss, EXPLOSION_X, EXPLOSION_Y, 0x0C));
            boss.recordChild(ChildKind.BOSS_EXPLOSION, controller);
            if (subtype == 0) {
                boss.recordChild(ChildKind.EXPLOSION_SEQUENCER,
                        boss.spawnFreeChild(() -> new ExplosionSequencerChild(boss)));
            }
            ObjectLifetimeOps.expireDynamic(this);
        }
    }

    /** Explosion sequencer (loc_72DEA): drives the launch milestones. */
    private static final class ExplosionSequencerChild extends LaunchForegroundChild {
        private int interval = 5;
        private int timer;
        private int count;
        private int milestoneWait = -1;

        private ExplosionSequencerChild(LbzFinalBoss1Instance boss) {
            super(boss, "LBZFinalBoss1ExplosionSequencer",
                    boss.cameraX() + 0xD0, boss.cameraY() - 0x20, 0);
            this.visible = false;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (milestoneWait >= 0) {
                if (milestoneWait-- <= 0) {
                    boss.signalLaunchMilestoneA();
                    boss.spawnDeathEggMiniatures(this);
                    ObjectLifetimeOps.expireDynamic(this);
                }
                return;
            }
            if (timer-- > 0) {
                return;
            }
            count++;
            if (count == 8) {
                boss.signalPadCollapseStart();
            }
            if (count >= 0x18) {
                // loc_72E22 falls through loc_72E2E on this dispatch.
                // Count $18 spawns no debris and consumes no random number.
                milestoneWait = 0x17E;
                return;
            }
            timer = interval++;
            spawnRandomExplosionDebris();
        }

        /** ROM loc_72E54: a Map_LBZDeathEggSmall debris sprite, not a boss explosion. */
        private void spawnRandomExplosionDebris() {
            boss.recordChild(ChildKind.BOSS_EXPLOSION,
                    boss.spawnFreeChild(() -> new DeathEggExplosionDebrisChild(boss)));
        }
    }

    /** loc_72E54 debris: static spawn that falls with MoveChkDel gravity. */
    private static final class DeathEggExplosionDebrisChild extends LaunchForegroundChild {
        private boolean initialized;
        private boolean randomizeOnInit;

        private DeathEggExplosionDebrisChild(LbzFinalBoss1Instance boss) {
            this(boss, 0, 0, 7);
            randomizeOnInit = true;
        }

        private DeathEggExplosionDebrisChild(LbzFinalBoss1Instance boss, int x, int y, int frame) {
            super(boss, "LBZFinalBoss1DeathEggExplosion", x, y, 0);
            this.mappingFrame = frame;
            this.visible = false;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // loc_72E54 returns after setup; MoveChkDel starts next dispatch.
            if (!initialized) {
                initialized = true;
                if (randomizeOnInit) {
                    // Random_Number belongs to this object's init, after allocation.
                    int random = boss.services().rng().nextRaw();
                    mappingFrame = new int[] {7, 8, 9, 0xA, 0xB, 7, 8, 9}[random & 7];
                    x = (boss.cameraX() + 0x20 + ((random >>> 16) & 0xFF)) & 0xFFFF;
                    y = (boss.cameraY() - 0x20) & 0xFFFF;
                }
                return;
            }
            visible = true;
            // MoveChkDel = MoveSprite (gravity $38) + delete out of range.
            moveSprite2();
            yVel += 0x38;
            if (!spriteCheckDeleteXYKeepsAlive()) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }
    }

    /** Death Egg miniature cluster (loc_72E9E). */
    private static final class DeathEggMiniatureChild extends LaunchForegroundChild {
        private static final int[][] OFFSETS = {
                {0, 0}, {-0x10, 0x28}, {-0x70, 0}, {-0x48, 0x28},
                {0x18, 0x10}, {-0x24, -8}, {-0x50, 0x1C}
        };
        /** byte_72EDA: (frame, extra render-flag bits). */
        private static final int[][] FRAME_FLAGS = {
                {0, 0}, {1, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {2, 3}
        };
        private static final int[] Y_VELS = {0x40, 0x38, 0x3C, 0x40, 0x44, 0x48, 0x4C};
        @RewindTransient(reason = "Constructor-derived miniature index.")
        private final int index;
        private boolean initialized;

        private DeathEggMiniatureChild(LbzFinalBoss1Instance boss, int baseX, int baseY, int index) {
            super(boss, "LBZFinalBoss1DeathEggMiniature",
                    baseX + OFFSETS[index][0],
                    baseY + OFFSETS[index][1],
                    index);
            this.index = index;
            this.visible = false;
            this.mappingFrame = FRAME_FLAGS[index][0];
            this.hFlip = (FRAME_FLAGS[index][1] & 1) != 0;
            this.vFlip = (FRAME_FLAGS[index][1] & 2) != 0;
            this.yVel = Y_VELS[index];
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (!initialized) {
                initialized = true;
                // loc_72E9E tails into PalLoad_Line1, without moving/drawing.
                boss.loadEndingPalette();
                return;
            }
            visible = true;
            if (index == 0) {
                // ROM loc_72EF8: every $10 frames spawn a smoke cluster.
                if ((vIntRunCount & 0x0F) == 0) {
                    spawnSmoke();
                }
                // ROM loc_72F2C: milestone B once the cluster passes below
                // camera.y + $80.
                if (unsignedCompare16(boss.cameraY() + 0x80, getY()) < 0) {
                    boss.signalLaunchMilestoneB();
                }
            }
            moveSprite2();
        }

        private void spawnSmoke() {
            int random = boss.services().rng().nextRaw();
            int smokeDx = (random & 0x1F) - 0x10;
            int smokeDy = ((random >>> 16) & 0x1F) - 0x10;
            int smokeX = (getX() + smokeDx) & 0xFFFF;
            int smokeY = (getY() + smokeDy) & 0xFFFF;
            boss.recordChild(ChildKind.DEATH_EGG_SMOKE,
                    spawnCurrentChild(() -> new DeathEggSmokeChild(boss, smokeX, smokeY)));
        }

        private static int unsignedCompare16(int a, int b) {
            return Integer.compare(a & 0xFFFF, b & 0xFFFF);
        }
    }

    /** Smoke cluster (loc_72F4C): frame 3, rises, rotates flips, spawns one sub-puff. */
    private static final class DeathEggSmokeChild extends LaunchForegroundChild {
        private int life = 0x7F;
        private int rotateState;
        private boolean puffSpawned;

        private DeathEggSmokeChild(LbzFinalBoss1Instance boss, int x, int y) {
            super(boss, "LBZFinalBoss1DeathEggSmoke", x, y, 0);
            this.mappingFrame = 3;
            this.yVel = -0x40;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (!puffSpawned) {
                puffSpawned = true;
                int puffX = getX();
                int puffY = getY();
                boss.recordChild(ChildKind.DEATH_EGG_SMOKE,
                        spawnCurrentChild(() -> new DeathEggSmokePuffChild(boss, puffX, puffY)));
            }
            // ROM loc_72F7A: rotate the two low render-flag bits every 4 frames.
            if ((vIntRunCount & 3) == 0) {
                rotateState = (rotateState + 1) & 3;
                hFlip = (rotateState & 1) != 0;
                vFlip = (rotateState & 2) != 0;
            }
            moveSprite2();
            if (--life < 0) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }
    }

    /** Smoke sub-puff (loc_72FB2): frames 4,4,5,6 at delay 2, sinks at +$40. */
    private static final class DeathEggSmokePuffChild extends LaunchForegroundChild {
        private static final int[] ANIM_FRAMES = {4, 4, 5, 6};
        private int animIndex;
        private int animTimer;

        private DeathEggSmokePuffChild(LbzFinalBoss1Instance boss, int x, int y) {
            super(boss, "LBZFinalBoss1DeathEggSmokePuff", x, y, 0);
            this.mappingFrame = ANIM_FRAMES[0];
            this.yVel = 0x40;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            moveSprite2();
            if (--animTimer < 0) {
                animTimer = 2;
                animIndex++;
                if (animIndex >= ANIM_FRAMES.length) {
                    ObjectLifetimeOps.expireDynamic(this);
                    return;
                }
                mappingFrame = ANIM_FRAMES[animIndex];
            }
        }
    }
}
