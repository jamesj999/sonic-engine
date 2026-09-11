package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcRequests;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;

import java.util.List;

/**
 * Sonic 2 Oil Ocean Zone boss (Obj55).
 *
 * <p>Reference: docs/s2disasm/s2.asm:68197-69000. Obj55 is a single ROM object
 * dispatcher with boss_subtype values for the main submarine, laser shooter,
 * spike chain, laser projectile, and ground wave.
 */
public class Sonic2OOZBossInstance extends AbstractBossInstance implements SpawnRewindRecreatable {
    private static final int SUB_MAIN = 0x02;
    private static final int SUB_LASER_SHOOTER = 0x04;
    private static final int SUB_SPIKE_CHAIN = 0x06;
    private static final int SUB_LASER = 0x08;

    private static final int MAIN_INIT = 0x00;
    private static final int MAIN_SURFACE = 0x02;
    private static final int MAIN_WAIT = 0x04;
    private static final int MAIN_DIVE = 0x06;
    private static final int MAIN_DEFEATED = 0x08;

    private static final int SHOOTER_RISE = 0x02;
    private static final int SHOOTER_CHOOSE_TARGET = 0x04;
    private static final int SHOOTER_AIM = 0x06;
    private static final int SHOOTER_LOWER = 0x08;

    private static final int LASER_MAIN = 0x02;
    private static final int WAVE_MAIN = 0x04;

    private static final int MAIN_X = 0x2940;
    private static final int MAIN_START_Y = 0x02D0;
    private static final int MAIN_SURFACE_Y = 0x0290;
    private static final int MAIN_DIVE_PEAK_Y = 0x028C;
    private static final int MAIN_WAIT_TIME = 0x00A8;
    private static final int SHOOTER_START_Y = 0x02B0;
    private static final int SHOOTER_TOP_Y = 0x0240;
    private static final int SPIKE_LEFT_X = 0x28C0;
    private static final int SPIKE_RIGHT_X = 0x29C0;
    private static final int SPIKE_Y = 0x02A0;
    private static final int PLAYER_SIDE_TEST_X = 0x293A;
    private static final int STATUS_RISING_DONE = 0x40;
    private static final int STATUS_HIT = 0x80;
    private static final int LASER_LEFT_DELETE_X = 0x2870;
    private static final int LASER_RIGHT_DELETE_X = 0x2A10;
    private static final int LASER_GROUND_Y = 0x0250;
    private static final int[] LASER_TARGETS = {0x238, 0x230, 0x240, 0x25F};
    private static final int MAX_CHILD_SPRITES = 8;
    private static final int MAIN_VEHICLE_CHILD_FRAME = 1;
    private static final int CHAIN_CHILD_FRAME = 7;
    private static final int[] WAVE_ANIMATION_FRAMES = {
            0x0D, 0x11, 0x0E, 0x12, 0x0F, 0x13, 0x10, 0x14,
            0x14, 0x10, 0x13, 0x0F, 0x12, 0x0E, 0x11, 0x0D
    };

    private int bossSubtype;
    private int bossCountdown;
    private int status;
    private int mainFrame;
    private int childSpriteCount;
    private int[] childFrames;
    private int[] childXs;
    private int[] childYs;
    private int collisionFlags;
    private int laserPosMask;
    private int shotCount;
    private int animFrameDuration;
    private int waveDelay;
    private int waveCount;
    private int waveAnimFrameIndex;
    private int touchCollisionX;
    private int touchCollisionY;
    private boolean touchCollisionSnapshotReady;
    private boolean flipped;
    private boolean bossDefeatedFlagSet;
    @RewindTransient(reason = "pending Obj55 laser init can reconstruct parent position/flip from its spawn fallback")
    private Sonic2OOZBossInstance laserParent;

    public Sonic2OOZBossInstance(ObjectSpawn spawn) {
        super(spawn, "OOZ Boss");
    }

    @Override
    protected void initializeBossState() {
        int subtype = spawn.subtype();
        if (subtype == SUB_LASER) {
            if (spawn.rawYWord() == WAVE_MAIN) {
                initializeWaveFromSpawn();
            } else {
                initializePendingLaserFromSpawn();
            }
            return;
        }
        // ROM Obj55_Init sets boss_subtype=2 and returns; Obj55_Main_Init runs on
        // the next object pass (docs/s2disasm/s2.asm:68225-68238,68258-68276).
        bossSubtype = SUB_MAIN;
        state.x = spawn.x();
        state.y = spawn.y();
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.routineSecondary = MAIN_INIT;
        state.hitCount = getInitialHitCount();
        mainFrame = 8;
        childSpriteCount = 0;
        collisionFlags = 0x0F;
        touchCollisionX = state.x;
        touchCollisionY = state.y;
        touchCollisionSnapshotReady = false;
        bossDefeatedFlagSet = false;
    }

    private void initializeMainVehicle(boolean faceLeft) {
        bossSubtype = SUB_MAIN;
        state.x = MAIN_X;
        state.y = MAIN_START_Y;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = 0;
        state.yVel = -0x80;
        state.routineSecondary = MAIN_SURFACE;
        // Obj55_Init seeds boss_hitcount2 once; Obj55_Main_Init only resets the
        // current pass state, collision, and collision routine. Preserve HP across
        // later laser/spike cycles (docs/s2disasm/s2.asm:68225-68238,68258-68283).
        state.sineCounter = 0;
        flipped = faceLeft;
        status = 0;
        mainFrame = 8;
        childSpriteCount = 1;
        setChildSprite(0, MAIN_VEHICLE_CHILD_FRAME, state.x, state.y);
        collisionFlags = 0x0F;
        touchCollisionX = state.x;
        touchCollisionY = state.y;
        touchCollisionSnapshotReady = false;
        laserPosMask = 0;
        shotCount = 0;
        animFrameDuration = 0;
        bossCountdown = 0;
        bossDefeatedFlagSet = false;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
        if (state.invulnerable && state.invulnerabilityTimer == 0x1F) {
            status |= STATUS_HIT;
        }

        switch (bossSubtype) {
            case SUB_MAIN -> updateMain(player, vIntRunCount);
            case SUB_LASER_SHOOTER -> updateLaserShooter(player);
            case SUB_SPIKE_CHAIN -> updateSpikeChain(player);
            case SUB_LASER -> updateLaserOrWave();
            default -> {
                bossSubtype = SUB_MAIN;
                state.routineSecondary = MAIN_INIT;
            }
        }
    }

    private void updateMain(PlayableEntity player, int vIntRunCount) {
        if (state.routineSecondary == MAIN_INIT) {
            initializeMainVehicle(isPlayerLeftOfArena(player));
            return;
        }
        switch (state.routineSecondary) {
            case MAIN_SURFACE -> updateMainSurface();
            case MAIN_WAIT -> updateMainWait();
            case MAIN_DIVE -> updateMainDive();
            case MAIN_DEFEATED -> updateMainDefeated(vIntRunCount);
            default -> state.routineSecondary = MAIN_INIT;
        }
    }

    private void updateMainSurface() {
        moveBossObject();
        state.x = MAIN_X;
        state.xFixed = state.x << 16;
        applyHoverPosition();
        alignMainVehicleChild();
        if ((state.yFixed >> 16) < MAIN_SURFACE_Y) {
            setBossYHighWord(MAIN_SURFACE_Y);
            state.routineSecondary = MAIN_WAIT;
            bossCountdown = MAIN_WAIT_TIME;
        }
    }

    private void updateMainWait() {
        if ((status & STATUS_HIT) == 0) {
            applyHoverPosition();
            alignMainVehicleChild();
            bossCountdown--;
            if (bossCountdown >= 0) {
                return;
            }
        }
        state.routineSecondary = MAIN_DIVE;
        state.yVel = -0x40;
    }

    private void updateMainDive() {
        moveBossObject();
        state.y = state.yFixed >> 16;
        state.x = MAIN_X;
        state.xFixed = state.x << 16;
        alignMainVehicleChild();
        if ((status & STATUS_RISING_DONE) == 0) {
            if (state.y >= MAIN_DIVE_PEAK_Y) {
                return;
            }
            setBossYHighWord(MAIN_DIVE_PEAK_Y);
            state.yVel = 0x80;
            status |= STATUS_RISING_DONE;
            return;
        }
        if (state.y < MAIN_START_Y) {
            return;
        }
        setBossYHighWord(MAIN_START_Y);
        state.routineSecondary = MAIN_INIT;
        bossSubtype = (status & STATUS_HIT) != 0 ? SUB_SPIKE_CHAIN : SUB_LASER_SHOOTER;
    }

    private void updateMainDefeated(int vIntRunCount) {
        bossCountdown--;
        if (bossCountdown >= 0) {
            if (bossCountdown < 0x1E) {
                mainFrame = 0x0B;
            } else if ((vIntRunCount & 0x07) == 0) {
                spawnDefeatExplosion();
            }
            return;
        }
        if (!bossDefeatedFlagSet) {
            ObjectServices services = tryServices();
            // ROM: Current_Boss_ID is NEVER cleared in Sonic 2. It is written only by
            // the boss-arena setup routines (`move.b #N,(Current_Boss_ID).w`, ids 1-9)
            // and read by `tst.b`; docs/s2disasm/s2.asm contains no `clr.b` or
            // `move.b #0` for it, so it resets only via the level-load RAM clear and
            // persists to the end of the act. Sonic_Boundary's right-hand test widens
            // the side boundary by $40 only when it is zero (s2.asm:37243-37251), so
            // clearing it here let the character run 64px past the ROM's clamp.
            // Contrast S1, which DOES clear at the Egg Prison
            // (s1disasm/_incObj/3E Prison Capsule.asm:97), and S3K, which clears
            // Boss_flag at 31 sites. S2 is the exception.
            if (services != null) {
                if (!Sonic2PlcRequests.append(services, Sonic2Constants.PLC_ANIMALS_OOZ,
                        Sonic2Constants.PLC_EXPLOSION)) return;
                services.playMusic(Sonic2Music.OIL_OCEAN.id);
            }
            bossDefeatedFlagSet = true;
        }
        ObjectServices services = tryServices();
        if (services != null && services.camera() != null && services.camera().getMaxX() < 0x2A20) {
            services.camera().setMaxX((short) (services.camera().getMaxX() + 2));
        }
        if (state.y < MAIN_START_Y) {
            state.y++;
            state.yFixed = state.y << 16;
            alignMainVehicleChild();
        } else if (services != null && services.camera() != null && services.camera().getMaxX() >= 0x2A20) {
            ObjectLifetimeOps.deleteNoRespawn(this);
        }
    }

    private void updateLaserShooter(PlayableEntity player) {
        if (state.routineSecondary == MAIN_INIT) {
            initializeLaserShooter(isPlayerRightOfArena(player));
            return;
        }
        switch (state.routineSecondary) {
            case SHOOTER_RISE -> updateShooterRise();
            case SHOOTER_CHOOSE_TARGET -> updateShooterChooseTarget();
            case SHOOTER_AIM -> updateShooterAim();
            case SHOOTER_LOWER -> updateShooterLower();
            default -> state.routineSecondary = MAIN_INIT;
        }
        facePlayerWithMargin(player);
        updateShooterWind();
    }

    private void initializeLaserShooter(boolean faceRight) {
        bossSubtype = SUB_LASER_SHOOTER;
        state.x = MAIN_X;
        state.y = SHOOTER_START_Y;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = 0;
        state.yVel = -0x80;
        state.routineSecondary = SHOOTER_RISE;
        state.sineCounter = 0;
        flipped = faceRight;
        mainFrame = 5;
        childSpriteCount = 8;
        initializeChainChildFrames();
        collisionFlags = 0x8A;
        touchCollisionX = state.x;
        touchCollisionY = state.y;
        touchCollisionSnapshotReady = false;
        laserPosMask = 0;
        animFrameDuration = 0;
        shotCount = 0;
    }

    private void updateShooterRise() {
        moveBossObject();
        if (state.y >= SHOOTER_TOP_Y) {
            return;
        }
        setBossYHighWord(SHOOTER_TOP_Y);
        state.yVel = 0;
        state.routineSecondary = SHOOTER_CHOOSE_TARGET;
        bossCountdown = 0x80;
        shotCount = 3;
    }

    private void updateShooterChooseTarget() {
        tickFiringFrame();
        bossCountdown--;
        if (bossCountdown != 0) {
            return;
        }
        shotCount--;
        if (shotCount < 0) {
            state.yVel = 0x80;
            state.routineSecondary = SHOOTER_LOWER;
            return;
        }
        int targetIndex = nextLaserTargetIndex();
        laserPosMask |= 1 << targetIndex;
        bossCountdown = LASER_TARGETS[targetIndex];
        state.routineSecondary = SHOOTER_AIM;
        moveTowardTarget();
    }

    private void updateShooterAim() {
        moveBossObject();
        if (state.yVel < 0) {
            if (bossCountdown < state.y) {
                return;
            }
        } else if (bossCountdown >= state.y) {
            return;
        }
        state.yVel = 0;
        animFrameDuration = 8;
        mainFrame = 6;
        spawnLaser();
        state.routineSecondary = SHOOTER_CHOOSE_TARGET;
        bossCountdown = 0x28;
        state.yVel = -0x80;
    }

    private void updateShooterLower() {
        tickFiringFrame();
        moveBossObject();
        if (state.y < SHOOTER_START_Y) {
            return;
        }
        setBossYHighWord(SHOOTER_START_Y);
        state.yVel = 0;
        state.routineSecondary = MAIN_INIT;
        bossSubtype = SUB_MAIN;
    }

    private void updateSpikeChain(PlayableEntity player) {
        if (state.routineSecondary == MAIN_INIT) {
            initializeSpikeChain(!isPlayerLeftOfArena(player));
            return;
        }
        updateSpikeChainMove();
        if (state.sineCounter >= 0xFE) {
            state.routineSecondary = MAIN_INIT;
            bossSubtype = SUB_LASER_SHOOTER;
            return;
        }
        updateSpikeFrame();
    }

    private void initializeSpikeChain(boolean rightSide) {
        bossSubtype = SUB_SPIKE_CHAIN;
        state.x = rightSide ? SPIKE_RIGHT_X : SPIKE_LEFT_X;
        state.y = SPIKE_Y;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = 0;
        state.yVel = 0;
        state.routineSecondary = 0x02;
        state.sineCounter = 0;
        flipped = rightSide;
        mainFrame = 2;
        childSpriteCount = 8;
        initializeChainChildFrames();
        collisionFlags = 0x8A;
        touchCollisionX = state.x;
        touchCollisionY = state.y;
        touchCollisionSnapshotReady = false;
    }

    private void updateLaserOrWave() {
        if (state.routineSecondary == MAIN_INIT) {
            initializeLaserFromParent();
            return;
        }
        if (state.routineSecondary == WAVE_MAIN) {
            updateWave();
        } else {
            updateLaser();
        }
    }

    @Override
    protected boolean skipsSameFrameUpdateAfterSpawn() {
        return bossSubtype == SUB_LASER && state.routineSecondary == MAIN_INIT;
    }

    private void initializePendingLaserFromSpawn() {
        bossSubtype = SUB_LASER;
        state.routineSecondary = MAIN_INIT;
        state.x = spawn.x();
        state.y = spawn.y();
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = 0;
        state.yVel = 0;
        mainFrame = 0x0C;
        childSpriteCount = 0;
        collisionFlags = 0;
        waveAnimFrameIndex = 0;
        touchCollisionX = state.x;
        touchCollisionY = state.y;
        touchCollisionSnapshotReady = false;
    }

    private void initializeLaserFromParent() {
        bossSubtype = SUB_LASER;
        state.routineSecondary = LASER_MAIN;
        Sonic2OOZBossInstance parent = laserParent;
        int parentX = parent != null ? parent.getPreUpdateX() : spawn.x();
        int parentY = parent != null ? parent.getPreUpdateY() : spawn.y();
        boolean parentFlipped = parent != null ? parent.flipped : (spawn.renderFlags() & 0x01) != 0;
        state.x = parentX + (parentFlipped ? 0x20 : -0x20);
        state.y = parentY;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = parentFlipped ? 0x400 : -0x400;
        state.yVel = 0;
        flipped = parentFlipped;
        mainFrame = 0x0C;
        childSpriteCount = 0;
        collisionFlags = 0xAF;
        touchCollisionX = state.x;
        touchCollisionY = state.y;
        touchCollisionSnapshotReady = false;
        laserParent = null;
    }

    private void initializeWaveFromSpawn() {
        bossSubtype = SUB_LASER;
        state.routineSecondary = WAVE_MAIN;
        state.x = spawn.x();
        state.y = spawn.y();
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = (spawn.renderFlags() & 0x01) != 0 ? 0x400 : -0x400;
        state.yVel = 0;
        flipped = (spawn.renderFlags() & 0x01) != 0;
        mainFrame = 0x0D;
        childSpriteCount = 0;
        collisionFlags = 0x8B;
        animFrameDuration = 0;
        waveAnimFrameIndex = 0;
        touchCollisionX = state.x;
        touchCollisionY = state.y;
        touchCollisionSnapshotReady = false;
        waveDelay = 5;
        waveCount = 7;
    }

    private void updateLaser() {
        checkLaserGroundWave();
        state.applyVelocity();
        if (state.x < LASER_LEFT_DELETE_X || state.x >= LASER_RIGHT_DELETE_X) {
            ObjectLifetimeOps.deleteNoRespawn(this);
        }
    }

    private void updateWave() {
        waveDelay--;
        if (waveDelay < 0) {
            waveDelay = 0xC7;
            waveCount--;
            if (waveCount >= 0) {
                spawnWaveSegment();
            }
        }
        animateWave();
    }

    private void animateWave() {
        animFrameDuration--;
        if (animFrameDuration >= 0) {
            return;
        }
        animFrameDuration = 1;
        if (waveAnimFrameIndex >= WAVE_ANIMATION_FRAMES.length) {
            state.routineSecondary = 0x06;
            collisionFlags = 0;
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }
        mainFrame = WAVE_ANIMATION_FRAMES[waveAnimFrameIndex++];
    }

    @Override
    public void snapshotTouchResponseState() {
        // Touch_Boss reads Obj55's object RAM before Obj55's next movement/write-back pass.
        if (touchCollisionSnapshotReady) {
            touchCollisionX = super.getPreUpdateX();
            touchCollisionY = super.getPreUpdateY();
        } else {
            touchCollisionX = state.x;
            touchCollisionY = state.y;
            touchCollisionSnapshotReady = true;
        }
        super.snapshotTouchResponseState();
    }

    @Override
    public int getPreUpdateX() {
        return touchCollisionX;
    }

    @Override
    public int getPreUpdateY() {
        return touchCollisionY;
    }

    private void checkLaserGroundWave() {
        if (state.y < LASER_GROUND_Y) {
            return;
        }
        if (state.xVel >= 0) {
            if (state.x >= 0x297C && state.x < 0x2980) {
                spawnGroundWave(0x2988);
            }
        } else if (state.x >= 0x2900 && state.x < 0x2904) {
            spawnGroundWave(0x28F8);
        }
    }

    private void moveBossObject() {
        state.applyVelocity();
    }

    private void setBossYHighWord(int y) {
        state.yFixed = (y << 16) | (state.yFixed & 0xFFFF);
    }

    private void applyHoverPosition() {
        int cosine = TrigLookupTable.cosHex(state.sineCounter & 0xFF);
        state.y = (state.yFixed >> 16) + (cosine >> 7);
        state.sineCounter = (state.sineCounter + 4) & 0xFF;
    }

    private void updateShooterWind() {
        int angle = state.sineCounter & 0xFF;
        int windX = (TrigLookupTable.cosHex(angle) >> 4);
        int windY = (TrigLookupTable.sinHex(angle) >> 6);
        state.x = MAIN_X + windX;
        state.y = (state.yFixed >> 16) + windY;
        for (int i = 0; i < childSpriteCount && i < MAX_CHILD_SPRITES; i++) {
            int childAngle = (angle - (0x10 * (i + 1))) & 0xFF;
            int childBaseY = (state.yFixed >> 16) + (0x0F * (i + 1));
            ensureChildSpriteStorage();
            childXs[i] = MAIN_X + (TrigLookupTable.cosHex(childAngle) >> 4);
            childYs[i] = childBaseY + (TrigLookupTable.sinHex(childAngle) >> 6);
            childFrames[i] = CHAIN_CHILD_FRAME;
        }
        state.sineCounter = (state.sineCounter + 2) & 0xFF;
    }

    private void updateSpikeChainMove() {
        int angle = (state.sineCounter + 0x40) & 0xFF;
        int baseX = flipped ? SPIKE_RIGHT_X : SPIKE_LEFT_X;
        int xOffset = (TrigLookupTable.cosHex(angle) * 0x68) >> 8;
        if (!flipped) {
            xOffset = -xOffset;
        }
        int yOffset = (TrigLookupTable.sinHex(angle) * 0x68) >> 8;
        state.x = baseX + xOffset;
        state.y = SPIKE_Y + yOffset;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        for (int i = 0; i < childSpriteCount && i < MAX_CHILD_SPRITES; i++) {
            int childAngle = (angle - (0x06 * (i + 1))) & 0xFF;
            int childXOffset = (TrigLookupTable.cosHex(childAngle) * 0x68) >> 8;
            if (!flipped) {
                childXOffset = -childXOffset;
            }
            int childYOffset = (TrigLookupTable.sinHex(childAngle) * 0x68) >> 8;
            ensureChildSpriteStorage();
            childXs[i] = baseX + childXOffset;
            childYs[i] = SPIKE_Y + childYOffset;
            childFrames[i] = CHAIN_CHILD_FRAME;
        }
        state.sineCounter = (state.sineCounter + 1) & 0xFF;
    }

    private void alignMainVehicleChild() {
        if (childSpriteCount > 0) {
            setChildSprite(0, MAIN_VEHICLE_CHILD_FRAME, state.x, state.y);
        }
    }

    private void initializeChainChildFrames() {
        for (int i = 0; i < MAX_CHILD_SPRITES; i++) {
            setChildSprite(i, CHAIN_CHILD_FRAME, state.x, state.y);
        }
    }

    private void setChildSprite(int index, int frame, int x, int y) {
        if (index < 0 || index >= MAX_CHILD_SPRITES) {
            return;
        }
        ensureChildSpriteStorage();
        childFrames[index] = frame;
        childXs[index] = x;
        childYs[index] = y;
    }

    private void ensureChildSpriteStorage() {
        if (childFrames == null) {
            childFrames = new int[MAX_CHILD_SPRITES];
            childXs = new int[MAX_CHILD_SPRITES];
            childYs = new int[MAX_CHILD_SPRITES];
        }
    }

    private void updateSpikeFrame() {
        int count = state.sineCounter & 0xFF;
        int frame = 0x15;
        if (count >= 0x52) {
            frame = 3;
            if (count >= 0x6B) {
                frame = 2;
                if (count >= 0x92) {
                    frame = 4;
                }
            }
        }
        mainFrame = frame;
    }

    private void tickFiringFrame() {
        animFrameDuration--;
        if (animFrameDuration == 0) {
            mainFrame = 5;
        }
    }

    private int nextLaserTargetIndex() {
        int seed = 0;
        ObjectServices services = tryServices();
        if (services != null && services.rng() != null) {
            seed = services.rng().nextWord();
        }
        int candidate = seed & 0x03;
        for (int i = 0; i < 4; i++) {
            candidate = (candidate + 1) & 0x03;
            if ((laserPosMask & (1 << candidate)) == 0) {
                return candidate;
            }
        }
        return 0;
    }

    private void moveTowardTarget() {
        state.yVel = bossCountdown - state.y < 0 ? -0x80 : 0x80;
    }

    private void spawnLaser() {
        ObjectSpawn laserSpawn = new ObjectSpawn(
                state.x, state.y, Sonic2ObjectIds.OOZ_BOSS, SUB_LASER, flipped ? 1 : 0, false, 0);
        spawnFreeChild(() -> {
            Sonic2OOZBossInstance laser = new Sonic2OOZBossInstance(laserSpawn);
            laser.laserParent = this;
            return laser;
        });
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(Sonic2Sfx.LASER_BURST.id);
        }
    }

    private void spawnGroundWave(int waveX) {
        ObjectSpawn waveSpawn = new ObjectSpawn(
                waveX, LASER_GROUND_Y, Sonic2ObjectIds.OOZ_BOSS, SUB_LASER,
                state.xVel >= 0 ? 1 : 0, false, WAVE_MAIN);
        spawnChild(() -> new Sonic2OOZBossInstance(waveSpawn));
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(Sonic2Sfx.LASER_FLOOR.id);
        }
    }

    private void spawnWaveSegment() {
        int offset = state.xVel < 0 ? -0x10 : 0x10;
        ObjectSpawn waveSpawn = new ObjectSpawn(
                state.x + offset, state.y, Sonic2ObjectIds.OOZ_BOSS, SUB_LASER,
                state.xVel >= 0 ? 1 : 0, false, WAVE_MAIN);
        spawnChild(() -> new Sonic2OOZBossInstance(waveSpawn));
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(Sonic2Sfx.LASER_FLOOR.id);
        }
    }

    private boolean isPlayerLeftOfArena(PlayableEntity player) {
        return player != null && (player.getCentreX() & 0xFFFF) < PLAYER_SIDE_TEST_X;
    }

    private boolean isPlayerRightOfArena(PlayableEntity player) {
        return player != null && (player.getCentreX() & 0xFFFF) >= PLAYER_SIDE_TEST_X;
    }

    private void facePlayerWithMargin(PlayableEntity player) {
        if (player == null) {
            return;
        }
        int dx = (player.getCentreX() & 0xFFFF) - state.x;
        if (dx < 0) {
            if (dx + 8 <= 0) {
                flipped = false;
            }
        } else if (dx - 8 >= 0) {
            flipped = true;
        }
    }

    @Override
    public int getCollisionFlags() {
        if (state.defeated) {
            return 0;
        }
        if (bossSubtype == SUB_MAIN) {
            return super.getCollisionFlags();
        }
        return collisionFlags;
    }

    @Override
    public boolean isPersistent() {
        // Obj55_ReleaseCamera owns the post-defeat camera release and delete gate:
        // it advances Camera_Max_X_pos to $2A20, then deletes only after the body
        // has sunk to y_pos >= $2D0 (docs/s2disasm/s2.asm:68443-68457).
        return bossSubtype == SUB_MAIN;
    }

    @Override
    protected int getInitialHitCount() {
        return 8;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        status |= STATUS_HIT;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return 0x0F;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;
    }

    @Override
    protected boolean defeatDeferralAppliesToThisBoss() {
        // Obj55_HandleHits runs at Obj55_Main_End after the current main routine
        // has already dispatched; Boss_Defeat writes boss_routine=8 for the next
        // object pass (docs/s2disasm/s2.asm:68355-68359,61270-61275).
        return true;
    }

    @Override
    protected void onDefeatStarted() {
        if (!isDefeatEntryPrepared() && !Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE)) return;
        bossSubtype = SUB_MAIN;
        state.routineSecondary = MAIN_DEFEATED;
        bossCountdown = DEFEAT_TIMER_START;
        mainFrame = 8;
        childSpriteCount = 1;
        alignMainVehicleChild();
        collisionFlags = 0;
    }

    @Override
    protected boolean prepareDefeatEntry() {
        return Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE);
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

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.OOZ_BOSS);
        if (renderer == null) {
            return;
        }
        ensureChildSpriteStorage();
        int count = Math.min(childSpriteCount, MAX_CHILD_SPRITES);
        for (int i = count - 1; i >= 0; i--) {
            renderer.drawFrameIndex(childFrames[i], childXs[i], childYs[i], flipped, false);
        }
        renderer.drawFrameIndex(mainFrame, state.x, state.y, flipped, false);
    }

    public int getBossSubtypeForTesting() {
        return bossSubtype;
    }

    public boolean hasRaisedBossDefeatedFlag() {
        // LevEvents_OOZ2_Routine4 keys off Boss_defeated_flag, which Obj55 raises
        // after its defeat countdown, not when hitcount first reaches zero.
        // docs/s2disasm/s2.asm:21396-21400,68435-68447.
        return bossDefeatedFlagSet;
    }

    public int getMainFrameForTesting() {
        return mainFrame;
    }

    public int getChildSpriteCountForTesting() {
        return childSpriteCount;
    }

    public int getStatusForTesting() {
        return status;
    }
}
