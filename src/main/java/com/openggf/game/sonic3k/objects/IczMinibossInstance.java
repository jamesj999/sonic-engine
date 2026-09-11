package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.bosses.S3kSharedBossCameraGate;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Level;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchResponseProvider.TouchRegion;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;

import java.util.List;

/**
 * IceCap Act 1 miniboss (object 0xBC).
 *
 * <p>ROM anchor: {@code Obj_ICZMiniboss} in {@code sonic3k.asm}. The boss body
 * starts with six hits, collision size $06, six visual ice shards, and eight
 * orbiting ice orbs. The state flow follows the ROM's wait-callback structure
 * around {@code loc_711EC..loc_713D2}; the orb/projectile children are modeled
 * as owned state so rewind captures the encounter with the parent.
 */
public final class IczMinibossInstance extends AbstractBossInstance implements SpawnRewindRecreatable {

    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_DESCEND = 0x02;
    private static final int ROUTINE_WAIT = 0x04;
    private static final int ROUTINE_PALETTE_SPINUP = 0x06;
    private static final int ROUTINE_DROP = 0x08;
    private static final int ROUTINE_ARC = 0x0A;
    private static final int ROUTINE_ORB_PREP = 0x0C;
    private static final int ROUTINE_ORB_ATTACK = 0x0E;
    private static final int ROUTINE_PALETTE_SLOWDOWN = 0x10;
    private static final int ROUTINE_RECOVER_WAIT = 0x12;
    private static final int ROUTINE_RISE = 0x14;
    private static final int ROUTINE_DEFEATED = 0x16;

    private static final int ORB_ROUTINE_WAIT_PARENT_ARM = 0x02;
    private static final int ORB_ROUTINE_RISE_FROM_FLOOR = 0x04;
    private static final int ORB_ROUTINE_ATTACH_TO_RING = 0x06;
    private static final int ORB_ROUTINE_ORBIT_UNTIL_PARENT_READY = 0x08;
    private static final int ORB_ROUTINE_WAIT_FOR_RELEASE_ANGLE = 0x0A;
    private static final int ORB_ROUTINE_RELEASE_PARENT_GATE = 0x0C;
    private static final int ORB_ROUTINE_MOVE_TO_THROW_TARGET = 0x0E;
    private static final int ORB_ROUTINE_THROW_UPWARD = 0x10;
    private static final int ORB_ROUTINE_WAIT_ABOVE_ARENA = 0x12;
    private static final int ORB_ROUTINE_DROP_PROJECTILE = 0x14;

    private static final int SHARD_ROUTINE_WAIT_RELEASE = 0x02;
    private static final int SHARD_ROUTINE_MOVE = 0x04;
    private static final int SHARD_ROUTINE_STOPPED = 0x06;

    private static final int HIT_COUNT = 6;
    private static final int SST_CHILD_COUNT = 14;
    private static final int COLLISION_SIZE = 0x06;
    private static final int INVULNERABILITY_TIME = 0x20;
    private static final int BODY_PALETTE_LINE = 1;
    private static final int ORB_PALETTE_LINE = 2;

    private static final int ARENA_X_MIN = 0x05F0;
    private static final int ARENA_X_MAX = 0x07F0;
    private static final int UPPER_ROUTE_CAMERA_Y_MIN = 0x0000;
    private static final int UPPER_ROUTE_CAMERA_Y_MAX = 0x0378;
    private static final int UPPER_ROUTE_ANCHOR_Y = 0x02B8;
    private static final int LOWER_ROUTE_CAMERA_Y_MIN = 0x07C8;
    private static final int LOWER_ROUTE_CAMERA_Y_MAX = 0x09C8;
    private static final int LOWER_ROUTE_ANCHOR_Y = 0x08C8;
    private static final int ARENA_ANCHOR_X = 0x06F0;

    private static final int BOSS_GATE_FADE_TIME = 2 * 60;
    private static final int DESCEND_TIME = 0xBF;
    private static final int RELEASE_SHARDS_TIME = 0x1F;
    private static final int SPINUP_TIME = 0x3F;
    private static final int ORB_ARM_TIME = 0x7F;
    private static final int DROP_TIME = 0x07;
    private static final int ARC_TIME = 0x5F;
    private static final int ORB_PREP_TIME = 0x3F;
    private static final int ORB_ATTACK_TIME = 0x7F;
    private static final int PALETTE_SLOWDOWN_TIME = 0x68;
    private static final int RECOVER_WAIT_TIME = 0x3F;
    private static final int RISE_TIME = 0x17;
    private static final int DEFEAT_TIME = 0x3F;
    private static final int DEFEAT_FLOW_OVERLAP_ENTRIES = 0x1D;

    private static final int PARENT_FLAG_ORB_RELEASE = 1 << 1; // $38 bit 1
    private static final int PARENT_FLAG_ORBS_ARMED = 1 << 2;  // $38 bit 2
    private static final int PARENT_FLAG_SHARDS_RELEASED = 1 << 3; // $38 bit 3

    private static final int[] ATTACK_PASS_COUNTS = {1, 0, 1, 1, 0, 1, 0, 0};
    private static final int[] SHARD_FRAMES = {1, 9, 3, 2, 10, 4};
    private static final int[] SHARD_OFFSET_X = {-0x0E, 0x0E, 0, -0x0E, 0x0E, 0};
    private static final int[] SHARD_OFFSET_Y = {-0x0B, -0x0B, 0x12, -0x0B, -0x0B, 0x0E};
    private static final int[] SHARD_PACKED_VELS = {-1, 0x1FF, 1, -1, 0x1FF, 1};
    private static final int[] SHARD_WAIT_TIMES = {2, 2, 3, 6, 6, 8};
    private static final int[] HIT_FLASH_COLOR_INDICES = {0x0A, 0x0B};
    private static final int[] HIT_FLASH_NORMAL_COLORS = {0x0222, 0x0020};
    private static final int[] HIT_FLASH_BRIGHT_COLORS = {0x0EEE, 0x0EEE};
    private static final int[] ORB_START_X = {-0x20, 0x20, 0x60, 0xA0, 0xE0, 0x120, 0x160, 0x1A0};
    private static final int[][] ORB_THROW_VELS = {
            {-0x300, -0x400}, {-0x200, -0x400}, {-0x100, -0x400}, {0, -0x400},
            {0x100, -0x400}, {0x200, -0x400}, {0x300, -0x400}, {0x400, -0x400}
    };
    private static final int[] ORB_TARGET_RIGHT = {
            0x60, 0x5C, 0x58, 0x54, 0x50, 0x4C, 0x48, 0x44
    };
    private static final int[] ORB_TARGET_LEFT = {
            0xE0, 0xDC, 0xD8, 0xD4, 0xD0, 0xCC, 0xC8, 0xC4
    };
    private static final int[] ORB_ANGLE_LOOKUP = {
            0x00, 0x01, 0x02, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
            0x12, 0x13, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A,
            0x1B, 0x1C, 0x1D, 0x1E, 0x1E, 0x1F, 0x20, 0x21,
            0x22, 0x23, 0x24, 0x24, 0x25, 0x26, 0x27, 0x27,
            0x28, 0x29, 0x29, 0x2A, 0x2A, 0x2B, 0x2B, 0x2C,
            0x2C, 0x2D, 0x2D, 0x2E, 0x2E, 0x2E, 0x2F, 0x2F,
            0x2F, 0x2F, 0x2F, 0x30, 0x30, 0x30, 0x30, 0x30
    };

    private ShardState[] shards;
    private OrbState[] orbs;
    private WaitCallback waitCallback;
    private int routineTimer;
    private int attackPatternIndex;
    private int attackPassCounter;
    private int arcXVelocityLatch;
    private int parentFlags;
    private int arenaAnchorX;
    private int arenaAnchorY;
    private S3kSharedBossCameraGate cameraGate;
    private boolean arenaGateInitialized;
    private boolean arenaGateComplete;
    private boolean bossMusicStarted;
    private boolean shardsReleased;
    private boolean orbThrowRight;
    private int mappingFrame;
    private int defeatTimer;
    private boolean hitFlashBright;
    private boolean hitFlashDirty;
    private int[] hitFlashPaletteWords = HIT_FLASH_NORMAL_COLORS;
    private boolean defeatRenderComplete;
    private boolean childSlotsReserved;

    private enum WaitCallback {
        NONE,
        RELEASE_SHARDS,
        START_SPINUP,
        ARM_ORBS,
        START_DROP,
        START_ARC,
        RESOLVE_ARC_WAIT,
        START_ORB_ATTACK,
        FINISH_ORB_ATTACK,
        START_RECOVER,
        START_RISE,
        RESTART_CYCLE
    }

    private enum OrbCallback {
        NONE,
        ATTACH_TO_RING,
        START_ORBIT,
        WAIT_ABOVE_ARENA,
        DROP_PROJECTILE,
        RESET_TO_WAIT_PARENT
    }

    public IczMinibossInstance(ObjectSpawn spawn) {
        super(spawn, "ICZMiniboss");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // ROM Obj_ICZMiniboss deletes on direct ICZ2 entry:
        // cmpi.w #$501,(Apparent_zone_and_act).w / beq Delete_Current_Sprite.
        // ICZ1's seamless reload changes Current_act to 1 but leaves
        // Apparent_act at 0, so the carried-over miniboss route remains live.
        if (services().currentAct() == 1 && services().apparentAct() == 1) {
            setDestroyed(true);
            return;
        }
        super.update(vIntRunCount, player);
    }

    @Override
    protected void initializeBossState() {
        state.x = spawn.x();
        state.y = spawn.y();
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = 0;
        state.yVel = 0x80;
        state.routine = ROUTINE_INIT;
        state.hitCount = HIT_COUNT;
        routineTimer = DESCEND_TIME;
        arenaAnchorX = spawn.x();
        arenaAnchorY = spawn.y();
        arenaGateInitialized = false;
        arenaGateComplete = false;
        bossMusicStarted = false;
        if (cameraGate == null) {
            cameraGate = new S3kSharedBossCameraGate();
        } else {
            cameraGate.reset();
        }
        waitCallback = WaitCallback.RELEASE_SHARDS;
        attackPatternIndex = 0;
        attackPassCounter = 0;
        arcXVelocityLatch = 0x200;
        parentFlags = 0;
        shardsReleased = false;
        orbThrowRight = true;
        mappingFrame = 0;
        defeatTimer = 0;
        hitFlashBright = false;
        hitFlashDirty = false;
        hitFlashPaletteWords = HIT_FLASH_NORMAL_COLORS;
        defeatRenderComplete = false;
        shards = new ShardState[6];
        orbs = new OrbState[8];
        initShards();
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
        updateHitFlash();

        if (!arenaGateComplete) {
            updateArenaGate();
            if (!arenaGateInitialized) {
                return;
            }
            updateShards();
            return;
        }

        switch (state.routine) {
            case ROUTINE_INIT -> {
                // loc_711EC creates the eight orb and six shard SSTs only
                // after loc_85CA4 has completed the camera gate.
                reserveNativeChildSlots();
                initOrbs(false);
                state.routine = ROUTINE_DESCEND;
            }
            case ROUTINE_DESCEND -> {
                moveWithVelocity();
                tickWait();
            }
            case ROUTINE_WAIT, ROUTINE_PALETTE_SPINUP, ROUTINE_ORB_PREP,
                    ROUTINE_PALETTE_SLOWDOWN, ROUTINE_RECOVER_WAIT -> tickWait();
            case ROUTINE_DROP -> {
                moveWithVelocity();
                tickWait();
            }
            case ROUTINE_ARC -> {
                updateArcPass();
            }
            case ROUTINE_ORB_ATTACK -> {
                if ((parentFlags & PARENT_FLAG_ORB_RELEASE) == 0) {
                    enterPaletteSlowdown();
                }
            }
            case ROUTINE_RISE -> {
                moveWithVelocity();
                tickWait();
            }
            case ROUTINE_DEFEATED -> updateDefeated();
            default -> {
            }
        }

        updateShards();
        updateOrbs();
    }

    private void reserveNativeChildSlots() {
        if (childSlotsReserved || getSlotIndex() < 0) {
            return;
        }
        ObjectServices services = servicesOrNull();
        if (services == null || services.objectManager() == null) {
            return;
        }
        childSlotsReserved = true;
        // Obj_ICZMiniboss creates eight orb SSTs followed by six shard SSTs
        // with the CreateChild after-current helpers at loc_711EC. Their logic
        // remains consolidated in this parent, but the native slots must stay
        // occupied for later AllocateObject and Obj37 cadence parity.
        services.objectManager().allocateChildSlotsAfter(
                spawn, SST_CHILD_COUNT, getSlotIndex());
    }

    @Override
    public int getReservedChildSlotCount() {
        return SST_CHILD_COUNT;
    }

    private void initShards() {
        for (int i = 0; i < shards.length; i++) {
            shards[i] = new ShardState(
                    state.x + SHARD_OFFSET_X[i],
                    state.y + SHARD_OFFSET_Y[i],
                    SHARD_OFFSET_X[i],
                    SHARD_OFFSET_Y[i],
                    SHARD_FRAMES[i],
                    SHARD_PACKED_VELS[i],
                    SHARD_WAIT_TIMES[i]);
        }
    }

    private void initOrbs(boolean aboveBoss) {
        int baseY = aboveBoss ? state.y - 0x40 : arenaAnchorY + 0xD8;
        for (int i = 0; i < orbs.length; i++) {
            OrbState orb = new OrbState(i, arenaAnchorX + ORB_START_X[i], baseY);
            resetOrbPosition(orb, baseY);
            orbs[i] = orb;
        }
    }

    private void updateArenaGate() {
        ObjectServices services = servicesOrNull();
        if (services == null) {
            arenaGateComplete = true;
            return;
        }
        if (!arenaGateInitialized) {
            if (services.camera() != null && !isCameraInRouteRange(services)) {
                if (isOutsideObjectLoadRange(services)) {
                    setDestroyed(true);
                }
                return;
            }
            initializeArenaGate(services);
            // Obj_ICZMiniboss's initialization dispatch calls sub_85D6A and
            // returns through PalLoad_Line1. The loc_85CA4 gate does not run
            // until the object's next SST dispatch, so its moving camera-bound
            // writes must likewise begin on the following frame.
            return;
        }
        arenaGateComplete = cameraGate.update(services.camera(), () -> {
            if (!bossMusicStarted) {
                services.playMusic(Sonic3kMusic.MINIBOSS.id);
                bossMusicStarted = true;
            }
        });
    }

    private void initializeArenaGate(ObjectServices services) {
        arenaGateInitialized = true;
        int subtype = spawn.subtype() & 0xFF;
        arenaAnchorY = subtype == 0 ? UPPER_ROUTE_ANCHOR_Y : LOWER_ROUTE_ANCHOR_Y;
        arenaAnchorX = ARENA_ANCHOR_X;

        if (services.gameState() != null) {
            services.gameState().setCurrentBossId(Sonic3kObjectIds.ICZ_MINIBOSS);
        }
        services.fadeOutMusic();
        installBossPalette(services);
        cameraGate.begin(
                services.camera(),
                new S3kSharedBossCameraGate.LockBounds(
                        arenaAnchorY, arenaAnchorY, arenaAnchorX, arenaAnchorX),
                BOSS_GATE_FADE_TIME);
        if (services.camera() != null) {
            services.camera().setMaxYTarget((short) arenaAnchorY);
        }
    }

    private boolean isCameraInRouteRange(ObjectServices services) {
        int cameraX = services.camera().getX() & 0xFFFF;
        int cameraY = services.camera().getY() & 0xFFFF;
        if (cameraX < ARENA_X_MIN || cameraX > ARENA_X_MAX) {
            return false;
        }
        if ((spawn.subtype() & 0xFF) == 0) {
            return cameraY >= UPPER_ROUTE_CAMERA_Y_MIN && cameraY <= UPPER_ROUTE_CAMERA_Y_MAX;
        }
        return cameraY >= LOWER_ROUTE_CAMERA_Y_MIN && cameraY <= LOWER_ROUTE_CAMERA_Y_MAX;
    }

    private boolean isOutsideObjectLoadRange(ObjectServices services) {
        int cameraX = services.camera().getX() & 0xFFFF;
        int cameraXCoarseBack = (cameraX - 0x80) & 0xFF80;
        int objectXCoarse = state.x & 0xFF80;
        int delta = (objectXCoarse - cameraXCoarseBack) & 0xFFFF;
        return delta > 0x280;
    }

    private void installBossPalette(ObjectServices services) {
        try {
            Level level = services.currentLevel();
            if (level == null) {
                return;
            }
            byte[] line = services.rom().readBytes(Sonic3kConstants.PAL_ICZ_MINIBOSS_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services.paletteOwnershipRegistryOrNull(),
                    level,
                    services.graphicsManager(),
                    S3kPaletteOwners.ICZ_MINIBOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    BODY_PALETTE_LINE,
                    line);
        } catch (Exception ignored) {
            // Palette setup is best-effort for headless/unit contexts without a ROM.
        }
    }

    private void tickWait() {
        if (--routineTimer >= 0) {
            return;
        }
        runWaitCallback();
    }

    private void runWaitCallback() {
        WaitCallback callback = waitCallback;
        waitCallback = WaitCallback.NONE;
        switch (callback) {
            case RELEASE_SHARDS -> enterReleaseShards();
            case START_SPINUP -> enterSpinup();
            case ARM_ORBS -> enterOrbArmWait();
            case START_DROP -> enterDrop();
            case START_ARC -> enterArc();
            case RESOLVE_ARC_WAIT -> resolveArcWait();
            case START_ORB_ATTACK -> enterOrbAttack();
            case FINISH_ORB_ATTACK -> enterPaletteSlowdown();
            case START_RECOVER -> enterRecoverWait();
            case START_RISE -> enterRise();
            case RESTART_CYCLE -> restartCycle();
            case NONE -> {
            }
        }
    }

    private void enterReleaseShards() {
        shardsReleased = true;
        parentFlags |= PARENT_FLAG_SHARDS_RELEASED;
        state.routine = ROUTINE_WAIT;
        routineTimer = RELEASE_SHARDS_TIME;
        waitCallback = WaitCallback.START_SPINUP;
    }

    private void enterSpinup() {
        state.routine = ROUTINE_PALETTE_SPINUP;
        routineTimer = SPINUP_TIME;
        waitCallback = WaitCallback.ARM_ORBS;
    }

    private void enterOrbArmWait() {
        parentFlags |= PARENT_FLAG_ORBS_ARMED;
        state.routine = ROUTINE_PALETTE_SPINUP;
        routineTimer = ORB_ARM_TIME;
        waitCallback = WaitCallback.START_DROP;
        playSfx(Sonic3kSfx.TUNNEL_BOOSTER.id);
    }

    private void enterDrop() {
        state.routine = ROUTINE_DROP;
        state.xVel = 0;
        state.yVel = 0x300;
        routineTimer = DROP_TIME;
        waitCallback = WaitCallback.START_ARC;
    }

    private void enterArc() {
        selectAttackPattern();
        enterArcPass();
    }

    private void enterArcPass() {
        state.routine = ROUTINE_ARC;
        state.yVel = 0x300;
        arcXVelocityLatch = -arcXVelocityLatch;
        state.xVel = arcXVelocityLatch;
        routineTimer = ARC_TIME;
        waitCallback = WaitCallback.NONE;
        playSfx(Sonic3kSfx.BOSS_ROTATE.id);
    }

    private void finishArcPass() {
        state.routine = ROUTINE_ORB_PREP;
        routineTimer = ORB_PREP_TIME;
        waitCallback = WaitCallback.RESOLVE_ARC_WAIT;
    }

    private void resolveArcWait() {
        attackPassCounter--;
        if (attackPassCounter >= 0) {
            enterArcPass();
            return;
        }
        enterOrbAttack();
    }

    private void enterOrbAttack() {
        state.routine = ROUTINE_ORB_ATTACK;
        parentFlags |= PARENT_FLAG_ORB_RELEASE;
        orbThrowRight = arenaAnchorX + 0xA0 >= state.x;
    }

    private void enterPaletteSlowdown() {
        state.routine = ROUTINE_PALETTE_SLOWDOWN;
        // word_71B52's palette-rotation script reaches loc_71390 after 105
        // dispatches. This is longer than the subsequent $3F Obj_Wait and
        // controls when the next orb cycle becomes touch-active.
        routineTimer = PALETTE_SLOWDOWN_TIME;
        waitCallback = WaitCallback.START_RECOVER;
        parentFlags &= ~PARENT_FLAG_ORBS_ARMED;
    }

    private void enterRecoverWait() {
        state.routine = ROUTINE_RECOVER_WAIT;
        routineTimer = RECOVER_WAIT_TIME;
        waitCallback = WaitCallback.START_RISE;
    }

    private void enterRise() {
        state.routine = ROUTINE_RISE;
        state.xVel = 0;
        state.yVel = -0x100;
        routineTimer = RISE_TIME;
        waitCallback = WaitCallback.RESTART_CYCLE;
    }

    private void restartCycle() {
        state.routine = ROUTINE_WAIT;
        state.xVel = 0;
        state.yVel = 0;
        routineTimer = 0xFF;
        waitCallback = WaitCallback.START_SPINUP;
    }

    private void selectAttackPattern() {
        attackPassCounter = ATTACK_PASS_COUNTS[attackPatternIndex & 7];
        attackPatternIndex++;
    }

    private void updateArcPass() {
        if (--routineTimer < 0) {
            finishArcPass();
            return;
        }
        state.yVel -= 0x10;
        moveWithVelocity();
    }

    private void updateShards() {
        for (ShardState shard : shards) {
            if (shard.routine == SHARD_ROUTINE_WAIT_RELEASE) {
                if ((parentFlags & PARENT_FLAG_SHARDS_RELEASED) != 0) {
                    shard.routine = SHARD_ROUTINE_MOVE;
                }
                shard.refreshFromParent(state.x, state.y);
            } else if (shard.routine == SHARD_ROUTINE_MOVE) {
                if (--shard.timer < 0) {
                    shard.routine = SHARD_ROUTINE_STOPPED;
                    shard.refreshFromParent(state.x, state.y);
                    continue;
                }
                shard.childDx += signedHighByte(shard.packedVelocity);
                shard.childDy += signedLowByte(shard.packedVelocity);
                shard.refreshFromParent(state.x, state.y);
            } else {
                shard.refreshFromParent(state.x, state.y);
            }
        }
    }

    private int signedLowByte(int value) {
        return (byte) (value & 0xFF);
    }

    private int signedHighByte(int value) {
        return (byte) ((value >>> 8) & 0xFF);
    }

    private void updateOrbs() {
        for (OrbState orb : orbs) {
            updateOrb(orb);
        }
    }

    private void updateOrb(OrbState orb) {
        switch (orb.routine) {
            case ORB_ROUTINE_WAIT_PARENT_ARM -> {
                if ((parentFlags & PARENT_FLAG_ORBS_ARMED) != 0) {
                    orb.routine = ORB_ROUTINE_RISE_FROM_FLOOR;
                    orb.yVel = -0x40;
                    orb.timer = 0x3F;
                    orb.callback = OrbCallback.ATTACH_TO_RING;
                }
            }
            case ORB_ROUTINE_RISE_FROM_FLOOR -> {
                orb.x += (orb.frameCounter++ & 1) == 0 ? 1 : -1;
                moveOrbWithVelocity(orb);
                tickOrbWait(orb);
            }
            case ORB_ROUTINE_ATTACH_TO_RING, ORB_ROUTINE_THROW_UPWARD, ORB_ROUTINE_DROP_PROJECTILE -> {
                moveOrbWithVelocity(orb);
                tickOrbWait(orb);
            }
            case ORB_ROUTINE_ORBIT_UNTIL_PARENT_READY -> {
                if ((parentFlags & PARENT_FLAG_ORB_RELEASE) != 0) {
                    orb.routine = ORB_ROUTINE_WAIT_FOR_RELEASE_ANGLE;
                } else {
                    orbitAcquire(orb);
                }
            }
            case ORB_ROUTINE_WAIT_FOR_RELEASE_ANGLE -> {
                int releaseAngle = orbThrowRight ? 0x00 : 0x80;
                if ((orb.angleD & 0xFF) == releaseAngle) {
                    orb.routine = ORB_ROUTINE_RELEASE_PARENT_GATE;
                } else {
                    orbitAcquire(orb);
                }
            }
            case ORB_ROUTINE_RELEASE_PARENT_GATE -> updateOrbReleaseParentGate(orb);
            case ORB_ROUTINE_MOVE_TO_THROW_TARGET -> updateOrbMoveToThrowTarget(orb);
            case ORB_ROUTINE_WAIT_ABOVE_ARENA -> tickOrbWait(orb);
            default -> {
            }
        }
    }

    private void tickOrbWait(OrbState orb) {
        if (--orb.timer >= 0) {
            return;
        }
        runOrbCallback(orb);
    }

    private void runOrbCallback(OrbState orb) {
        OrbCallback callback = orb.callback;
        orb.callback = OrbCallback.NONE;
        switch (callback) {
            case ATTACH_TO_RING -> {
                orb.routine = ORB_ROUTINE_ATTACH_TO_RING;
                orb.priority = 0x180;
                calculateOrbAttachVelocity(orb);
                orb.timer = 0x1F;
                orb.callback = OrbCallback.START_ORBIT;
            }
            case START_ORBIT -> {
                orb.routine = ORB_ROUTINE_ORBIT_UNTIL_PARENT_READY;
                orb.collisionFlags = 0x8B;
                playSfx(Sonic3kSfx.BOSS_ROTATE.id);
            }
            case WAIT_ABOVE_ARENA -> {
                orb.routine = ORB_ROUTINE_WAIT_ABOVE_ARENA;
                orb.timer = 0x7F;
                orb.callback = OrbCallback.DROP_PROJECTILE;
                resetOrbAboveArena(orb);
            }
            case DROP_PROJECTILE -> {
                orb.routine = ORB_ROUTINE_DROP_PROJECTILE;
                playSfx(Sonic3kSfx.LEVEL_PROJECTILE.id);
                orb.yVel = 0x400;
                orb.xVel = 0;
                orb.timer = 0x45;
                orb.callback = OrbCallback.RESET_TO_WAIT_PARENT;
            }
            case RESET_TO_WAIT_PARENT -> {
                orb.routine = ORB_ROUTINE_WAIT_PARENT_ARM;
                orb.collisionFlags = 0;
            }
            case NONE -> {
            }
        }
    }

    private void orbitAcquire(OrbState orb) {
        orb.angleC = (orb.angleC + 4) & 0xFF;
        moveOrbAtAngleLookup(orb);
        orb.angleD = (orb.angleD + 1) & 0xFF;
        adjustOrbVerticalAndFrame(orb, orb.angleD);
    }

    private void updateOrbReleaseParentGate(OrbState orb) {
        orb.angleC = (orb.angleC + 4) & 0xFF;
        moveOrbAtAngleLookup(orb);
        adjustOrbVerticalAndFrame(orb, 0);
        int releaseAngle = orbThrowRight ? 0x00 : 0x80;
        if ((parentFlags & PARENT_FLAG_ORB_RELEASE) == 0
                || (orb.index == 0 && (orb.angleC & 0xFF) == releaseAngle)) {
            orb.routine = ORB_ROUTINE_MOVE_TO_THROW_TARGET;
            parentFlags &= ~PARENT_FLAG_ORB_RELEASE;
            calculateOrbThrowVelocity(orb);
        }
    }

    private void updateOrbMoveToThrowTarget(OrbState orb) {
        if ((orb.angleC & 0xFF) == (orb.targetAngle & 0xFF)) {
            orb.routine = ORB_ROUTINE_THROW_UPWARD;
            orb.timer = 0x3F;
            orb.callback = OrbCallback.WAIT_ABOVE_ARENA;
            return;
        }
        orb.angleC = (orb.angleC + 4) & 0xFF;
        moveOrbAtAngleLookup(orb);
        adjustOrbVerticalAndFrame(orb, 0);
    }

    private void calculateOrbAttachVelocity(OrbState orb) {
        int romSubtype = orb.index << 1;
        orb.angleC = (romSubtype << 4) & 0xFF;
        int startX = orb.x;
        int startY = orb.y;
        moveOrbAtAngleLookup(orb);
        int targetX = orb.x;
        int targetY = orb.y;
        orb.x = startX;
        orb.y = startY;
        orb.xVel = (targetX - startX) << 3;
        orb.yVel = (targetY - startY) << 3;
    }

    private void calculateOrbThrowVelocity(OrbState orb) {
        int[] vel = ORB_THROW_VELS[orb.index];
        orb.xVel = orbThrowRight ? vel[0] : -vel[0];
        orb.yVel = vel[1];
        orb.targetAngle = orbThrowRight ? ORB_TARGET_RIGHT[orb.index] : ORB_TARGET_LEFT[orb.index];
    }

    private void resetOrbAboveArena(OrbState orb) {
        resetOrbPosition(orb, arenaAnchorY - 0x40);
    }

    private void resetOrbPosition(OrbState orb, int baseY) {
        int xJitter = 0;
        int yJitter = 0;
        ObjectServices services = servicesOrNull();
        if (services != null && services.rng() != null) {
            int random = services.rng().nextRaw();
            xJitter = (random & 7) - 3;
            yJitter = ((random >>> 16) & 7) - 3;
        }
        orb.x = arenaAnchorX + ORB_START_X[orb.index] + xJitter;
        orb.y = baseY + yJitter;
        orb.xSub = 0;
        orb.ySub = 0;
    }

    private void moveOrbAtAngleLookup(OrbState orb) {
        int angle = orb.angleC & 0xFF;
        int quadrant = (angle >>> 6) & 3;
        int index = angle & 0x3F;
        int inverseIndex = (~index) & 0x3F;
        int dx;
        int dy;
        switch (quadrant) {
            case 0 -> {
                dx = ORB_ANGLE_LOOKUP[index];
                dy = ORB_ANGLE_LOOKUP[inverseIndex];
            }
            case 1 -> {
                dx = ORB_ANGLE_LOOKUP[inverseIndex];
                dy = -ORB_ANGLE_LOOKUP[index];
            }
            case 2 -> {
                dx = -ORB_ANGLE_LOOKUP[index];
                dy = -ORB_ANGLE_LOOKUP[inverseIndex];
            }
            default -> {
                dx = -ORB_ANGLE_LOOKUP[inverseIndex];
                dy = ORB_ANGLE_LOOKUP[index];
            }
        }
        orb.x = state.x + dx;
        orb.y = state.y + dy;
    }

    private void adjustOrbVerticalAndFrame(OrbState orb, int sineAngle) {
        int cosine = TrigLookupTable.cosHex(sineAngle);
        if (cosine < 0) {
            cosine = -cosine;
        }
        int yDelta = Math.abs(orb.y - state.y);
        int projected = (cosine * yDelta) >> 8;
        boolean negatedYOffset = (orb.y & 0xFFFF) < (state.y & 0xFFFF);
        if (isUnsignedByteInRange(orb.angleD, 0x40, 0xC0)) {
            negatedYOffset = !negatedYOffset;
        }
        orb.y = state.y + (negatedYOffset ? -projected : projected);

        boolean lowPriority = (orb.angleD & 0x80) == 0;
        if (isUnsignedByteInRange(orb.angleC, 0x40, 0xC0)) {
            lowPriority = !lowPriority;
        }
        orb.frame = 6;
        int angle = orb.angleD & 0xFF;
        if ((angle >= 0x20 && angle < 0x60) || (angle >= 0xA0 && angle < 0xE0)) {
            orb.frame = lowPriority ? 5 : 8;
        }
        orb.priority = lowPriority ? 0x180 : 0x300;
        orb.front = !lowPriority;
    }

    private boolean isUnsignedByteInRange(int value, int lowInclusive, int highExclusive) {
        int unsigned = value & 0xFF;
        return unsigned >= lowInclusive && unsigned < highExclusive;
    }

    private void moveOrbWithVelocity(OrbState orb) {
        orb.xSub += orb.xVel;
        orb.ySub += orb.yVel;
        orb.x += orb.xSub >> 8;
        orb.y += orb.ySub >> 8;
        orb.xSub &= 0xFF;
        orb.ySub &= 0xFF;
    }

    private void moveWithVelocity() {
        state.applyVelocity();
    }

    private void updateHitFlash() {
        if (!state.invulnerable) {
            if (hitFlashDirty) {
                applyHitFlashPalette(false);
                hitFlashDirty = false;
            }
            return;
        }
        applyHitFlashPalette((state.invulnerabilityTimer & 1) == 0);
        hitFlashDirty = true;
        state.invulnerabilityTimer--;
        if (state.invulnerabilityTimer <= 0) {
            state.invulnerable = false;
            applyHitFlashPalette(false);
            hitFlashDirty = false;
        }
    }

    private void applyHitFlashPalette(boolean bright) {
        hitFlashBright = bright;
        hitFlashPaletteWords = bright ? HIT_FLASH_BRIGHT_COLORS : HIT_FLASH_NORMAL_COLORS;

        ObjectServices services = servicesOrNull();
        if (services == null) {
            return;
        }
        try {
            Level level = services.currentLevel();
            if (level == null || level.getPaletteCount() <= BODY_PALETTE_LINE) {
                return;
            }
            S3kPaletteWriteSupport.applyColors(
                    services.paletteOwnershipRegistryOrNull(),
                    level,
                    services.graphicsManager(),
                    S3kPaletteOwners.ICZ_MINIBOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    BODY_PALETTE_LINE,
                    HIT_FLASH_COLOR_INDICES,
                    hitFlashPaletteWords);
        } catch (Exception ignored) {
            // Palette flashing is best-effort for headless/unit contexts without a full level.
        }
    }

    private void updateDefeated() {
        if (defeatTimer < 0 || --defeatTimer >= 0) {
            return;
        }
        // BossDefeated writes $3F to the body timer. Wait_FadeToLevelMusic
        // reaches loc_713E8 on the 64th following body dispatch and sets bit 5
        // on the active loc_8B660 snow owner. Child6_CreateBossExplosion is a
        // separate SST and continues emitting after this boundary.
        stopActiveSnowdustEmitter();
    }

    void onDefeatExplosionControllerFinished() {
        if (defeatRenderComplete) {
            return;
        }
        defeatRenderComplete = true;
        spawnChild(IczMinibossPostBossPaletteController::new);
        // The ROM boss body changes into Wait_FadeToLevelMusic after its $3F
        // wait while Child6_CreateBossExplosion continues in another SST. The
        // engine folds that child's remaining 31 emission entries into this
        // owner, so carry those already-elapsed entries into its first dispatch.
        spawnChild(() -> new S3kBossDefeatSignpostFlow(
                state.x, 0, S3kBossDefeatSignpostFlow.CleanupAction.RESTORE_ICZ2_OBJECT_PALETTE,
                DEFEAT_FLOW_OVERLAP_ENTRIES, 0, 0, 0, true));
        setDestroyed(true);
    }

    private void stopActiveSnowdustEmitter() {
        ObjectServices services = servicesOrNull();
        if (services == null || services.objectManager() == null) {
            return;
        }
        // loc_713E8 follows _unkFAAE, verifies loc_8B660, then sets $38 bit 5.
        // The emitter records itself as the active ICZ snow owner; stop that
        // object rather than inferring anything from zone, route, or frame.
        for (IczSnowPileObjectInstance snow
                : services.objectManager().activeObjectsOfType(IczSnowPileObjectInstance.class)) {
            if (snow.isSnowdustEmitter()) {
                snow.stopSnowdustEmitter();
                return;
            }
        }
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        applyHit();
    }

    public void simulateHitForTest() {
        applyHit();
    }

    private void applyHit() {
        if (state.defeated || state.hitCount <= 0) {
            return;
        }
        state.hitCount--;
        if (state.hitCount <= 0) {
            state.hitCount = 0;
            state.defeated = true;
            state.invulnerable = false;
            applyHitFlashPalette(false);
            hitFlashDirty = false;
            state.routine = ROUTINE_DEFEATED;
            state.xVel = 0;
            state.yVel = 0;
            defeatTimer = DEFEAT_TIME;
            spawnChild(() -> new IczMinibossExplosionControllerChild(this, state.x, state.y));
            defeatRenderComplete = false;
            ObjectServices services = servicesOrNull();
            if (services != null) {
                if (services.gameState() != null) {
                    services.gameState().addScore(1000);
                }
                services.fadeOutMusic();
                // ROM loc_71926: jmp (BossDefeated_StopTimer).l
                // (sonic3k.asm:150472).
                stopLevelTimerOnBossDefeat();
            }
        } else {
            state.invulnerable = true;
            state.invulnerabilityTimer = INVULNERABILITY_TIME;
            applyHitFlashPalette(true);
            hitFlashDirty = true;
            playSfx(Sonic3kSfx.BOSS_HIT.id);
            onHitTaken(state.hitCount);
        }
    }

    @Override
    public int getCollisionFlags() {
        if (!isLiveBossRoutineActive()) {
            return 0;
        }
        if (state.defeated || state.invulnerable || state.hitCount <= 0) {
            return 0;
        }
        return 0xC0 | COLLISION_SIZE;
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        if (!isLiveBossRoutineActive()) {
            return null;
        }
        if (state.defeated || state.hitCount <= 0) {
            return null;
        }
        int bodyFlags = getCollisionFlags();
        int count = bodyFlags != 0 ? 1 : 0;
        for (OrbState orb : orbs) {
            if (orb.collisionFlags != 0) {
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        TouchRegion[] regions = new TouchRegion[count];
        int index = 0;
        if (bodyFlags != 0) {
            regions[index++] = new TouchRegion(state.x, state.y, bodyFlags);
        }
        for (OrbState orb : orbs) {
            if (orb.collisionFlags != 0) {
                regions[index++] = new TouchRegion(orb.x, orb.y, orb.collisionFlags);
            }
        }
        return regions;
    }

    /**
     * The miniboss exposes multiple touch regions (body + orbs) via
     * {@link #getMultiTouchRegions()}, so it declares its touch policy
     * explicitly instead of relying on the implicit default. It uses the
     * engine's standard multi-region enemy derivation — normal decode,
     * standard-enemy kill bounce, main-full/sidekick-hurt-only actor context,
     * and stop-after-first-overlap-for-main — matching the ROM's per-region
     * hit handling for a body-plus-satellites boss.
     */
    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TouchResponseProfile.fromProvider(this, multiRegionSource);
    }

    private boolean isLiveBossRoutineActive() {
        return arenaGateComplete && state.routine != ROUTINE_INIT;
    }

    @Override
    public int getCollisionProperty() {
        return state.hitCount;
    }

    @Override
    protected boolean usesBaseHitHandler() {
        return false;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;
    }

    @Override
    protected int getInitialHitCount() {
        return HIT_COUNT;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        mappingFrame = 0;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE;
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return INVULNERABILITY_TIME;
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic3kSfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic3kSfx.EXPLODE.id;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!arenaGateInitialized) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ICZ_MINIBOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        for (OrbState orb : orbs) {
            if (orb != null && !orb.front && orb.isVisible()) {
                renderer.drawFrameIndex(orb.frame, orb.x, orb.y, false, false, ORB_PALETTE_LINE);
            }
        }
        renderer.drawFrameIndex(mappingFrame, state.x, state.y, false, false, BODY_PALETTE_LINE);
        for (ShardState shard : shards) {
            if (shardsReleased) {
                renderer.drawFrameIndex(shard.frame, shard.x, shard.y, false, false, BODY_PALETTE_LINE);
            }
        }
        for (OrbState orb : orbs) {
            if (orb != null && orb.front && orb.isVisible()) {
                renderer.drawFrameIndex(orb.frame, orb.x, orb.y, false, false, ORB_PALETTE_LINE);
            }
        }
    }

    public int getCurrentRoutine() {
        return state.routine;
    }

    public int getRoutineTimerForTesting() {
        return routineTimer;
    }

    public int getYVelocityForTesting() {
        return state.yVel;
    }

    public int getXVelocityForTesting() {
        return state.xVel;
    }

    public int getOrbCountForTesting() {
        return orbs.length;
    }

    public int getShardCountForTesting() {
        return shards.length;
    }

    public int getShardXForTesting(int index) {
        return shards[index].x;
    }

    public int getShardYForTesting(int index) {
        return shards[index].y;
    }

    public int getShardRoutineForTesting(int index) {
        return shards[index].routine;
    }

    public int getOrbXForTesting(int index) {
        return orbs[index].x;
    }

    public int getOrbYForTesting(int index) {
        return orbs[index].y;
    }

    public int getOrbRoutineForTesting(int index) {
        return orbs[index] == null ? -1 : orbs[index].routine;
    }

    public int getOrbCollisionFlagsForTesting(int index) {
        return orbs[index].collisionFlags;
    }

    public int getOrbAngleCForTesting(int index) {
        return orbs[index].angleC;
    }

    public boolean isOrbVisibleForTesting(int index) {
        return orbs[index].isVisible();
    }

    public int getParentFlagsForTesting() {
        return parentFlags;
    }

    public int getOrbPaletteLineForTesting() {
        return ORB_PALETTE_LINE;
    }

    public boolean isArenaGateCompleteForTesting() {
        return arenaGateComplete;
    }

    public boolean isHitFlashBrightForTesting() {
        return hitFlashBright;
    }

    public int getHitFlashPaletteWordForTesting(int index) {
        return hitFlashPaletteWords[index];
    }

    private void playSfx(int soundId) {
        ObjectServices services = servicesOrNull();
        if (services != null) {
            services.playSfx(soundId);
        }
    }

    private ObjectServices servicesOrNull() {
        try {
            return tryServices();
        } catch (IllegalStateException e) {
            return null;
        }
    }


    private static final class ShardState
            implements com.openggf.game.rewind.RewindStateful<ShardState.RewindState> {
        private int x;
        private int y;
        private int childDx;
        private int childDy;
        private final int frame;
        private final int packedVelocity;
        private int timer;
        private int routine = SHARD_ROUTINE_WAIT_RELEASE;

        private record RewindState(int x, int y, int childDx, int childDy, int timer, int routine) {}

        private ShardState(int x, int y, int childDx, int childDy, int frame, int packedVelocity, int timer) {
            this.x = x;
            this.y = y;
            this.childDx = childDx;
            this.childDy = childDy;
            this.frame = frame;
            this.packedVelocity = packedVelocity;
            this.timer = timer;
        }

        private void refreshFromParent(int parentX, int parentY) {
            x = parentX + childDx;
            y = parentY + childDy;
        }

        @Override
        public RewindState captureRewindStateValue() {
            return new RewindState(x, y, childDx, childDy, timer, routine);
        }

        @Override
        public void restoreRewindStateValue(RewindState state) {
            this.x = state.x();
            this.y = state.y();
            this.childDx = state.childDx();
            this.childDy = state.childDy();
            this.timer = state.timer();
            this.routine = state.routine();
        }
    }

    private static final class OrbState
            implements com.openggf.game.rewind.RewindStateful<OrbState.RewindState> {
        private final int index;
        private int x;
        private int y;
        private int xVel;
        private int yVel;
        private int xSub;
        private int ySub;
        private int routine = ORB_ROUTINE_WAIT_PARENT_ARM;
        private int timer;
        private OrbCallback callback = OrbCallback.NONE;
        private int angleC;
        private int angleD;
        private int targetAngle;
        private int frame = 6;
        private int priority = 0x280;
        private int collisionFlags;
        private int frameCounter;
        private boolean front;

        private record RewindState(int x, int y, int xVel, int yVel, int xSub, int ySub,
                                   int routine, int timer, OrbCallback callback,
                                   int angleC, int angleD, int targetAngle,
                                   int frame, int priority, int collisionFlags,
                                   int frameCounter, boolean front) {}

        private OrbState(int index, int x, int y) {
            this.index = index;
            this.x = x;
            this.y = y;
        }

        @Override
        public RewindState captureRewindStateValue() {
            return new RewindState(x, y, xVel, yVel, xSub, ySub,
                    routine, timer, callback, angleC, angleD, targetAngle,
                    frame, priority, collisionFlags, frameCounter, front);
        }

        @Override
        public void restoreRewindStateValue(RewindState state) {
            this.x = state.x();
            this.y = state.y();
            this.xVel = state.xVel();
            this.yVel = state.yVel();
            this.xSub = state.xSub();
            this.ySub = state.ySub();
            this.routine = state.routine();
            this.timer = state.timer();
            this.callback = state.callback();
            this.angleC = state.angleC();
            this.angleD = state.angleD();
            this.targetAngle = state.targetAngle();
            this.frame = state.frame();
            this.priority = state.priority();
            this.collisionFlags = state.collisionFlags();
            this.frameCounter = state.frameCounter();
            this.front = state.front();
        }

        private boolean isVisible() {
            return true;
        }
    }
}
