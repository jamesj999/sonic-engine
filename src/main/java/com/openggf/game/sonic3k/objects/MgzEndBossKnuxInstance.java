package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossGradualMaxXExtender;

/**
 * S3K object $A2, the Knuckles-only Marble Garden Act 2 boss.
 *
 * <p>The locked-on ROM dispatches this object through a ground-fight state
 * machine ({@code Obj_MGZEndBossKnux}, {@code loc_6C72A..loc_6C88A}) rather
 * than through Sonic's flying/carry sequence.  It deliberately reuses the
 * MGZ drill vehicle's art, palette, hit handling and child rendering while
 * owning the distinct native routine/timer progression here.
 */
public final class MgzEndBossKnuxInstance extends MgzDrillingRobotnikInstance {
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_OPENING_WAIT = 0x02;
    private static final int ROUTINE_WAIT_FOR_CHILD = 0x04;
    private static final int ROUTINE_FIRST_WAIT = 0x06;
    private static final int ROUTINE_FIRST_DROP = 0x08;
    private static final int ROUTINE_SECOND_WAIT = 0x0A;
    private static final int ROUTINE_SECOND_DROP = 0x0C;
    private static final int ROUTINE_RESET = 0x0E;

    private static final int INITIAL_TIMER = 0x3F;
    private static final int FIRST_WAIT_TIMER = 0x1F;
    private static final int FIRST_DROP_TIMER = 0x87;
    private static final int SECOND_WAIT_TIMER = 0x3F;
    private static final int SECOND_DROP_TIMER = 0x9F;
    private static final int RESET_TIMER = (2 * 60) - 1;
    private static final int RESET_X = 0x3E18;
    private static final int RESET_Y = -0x58;
    private static final int INVULNERABLE_FRAMES = 0x20;
    private static final int DEFEAT_EXPLOSION_FRAMES = 0xB3;

    private int nativeRoutine;
    private int nativeTimer;
    private boolean childReady;
    private int collapseEmissionCount;
    private int defeatTimer;
    private boolean capsuleSpawned;
    private boolean resultsComplete;
    private boolean completionReady;
    private boolean drillChildSpawned;
    private boolean openingChildrenSpawned;
    private boolean defeatFadeStarted;
    private boolean defeatLocEntered;
    private boolean postResultsContinuation;
    private int postResultsInputTimer;
    private int postResultsStallTimer;
    private int postResultsLastX;
    private MotionSeed pendingFirstMotionSeed;
    private MotionSeed pendingSecondMotionSeed;
    private MotionSeed fa82MotionSeed;

    public record MotionSeed(int tableOffset, int angle, int baseX, int baseY) { }
    private static final int[][] DROP_OFFSETS = {
            {-0x0C, -0x2C}, {0x1A, -0x28}, {0x0C, -0x2C}, {-0x1A, -0x28},
            {0x14, 0x24}, {0x30, 0x1C}, {-0x14, 0x24}, {-0x30, 0x1C}
    };
    private static final int[][] FULL_VELOCITIES = {
            {-0x400, 0}, {-0x400, 0x400}, {0, 0x400}, {0x400, 0x400},
            {0x400, 0}, {0x400, -0x400}, {0, -0x400}, {-0x400, 0}
    };

    public MgzEndBossKnuxInstance(ObjectSpawn spawn) {
        super(spawn, false);
    }

    @Override
    protected void initializeBossState() {
        super.initializeBossState();
        state.x = getSpawn().x();
        state.y = getSpawn().y();
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.routine = ROUTINE_INIT;
        nativeRoutine = ROUTINE_INIT;
        nativeTimer = INITIAL_TIMER;
        childReady = false;
        collapseEmissionCount = 0;
        defeatTimer = 0;
        capsuleSpawned = false;
        resultsComplete = false;
        completionReady = false;
        drillChildSpawned = false;
        openingChildrenSpawned = false;
        defeatFadeStarted = false;
        defeatLocEntered = false;
        postResultsContinuation = false;
        postResultsInputTimer = 0;
        postResultsStallTimer = 0;
        postResultsLastX = 0;
        pendingFirstMotionSeed = null;
        pendingSecondMotionSeed = null;
        fa82MotionSeed = null;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
        prepareSharedBossPresentation();
        if (state.invulnerable && --state.invulnerabilityTimer <= 0) {
            state.invulnerable = false;
        }
        if (nativeRoutine == ROUTINE_INIT) {
            openingChildrenSpawned = true;
            drillChildSpawned = true;
            spawnChild(() -> new MgzEndBossKnuxDrillChild(this));
            enter(ROUTINE_OPENING_WAIT, INITIAL_TIMER);
            return;
        }
        if (nativeRoutine == ROUTINE_OPENING_WAIT) {
            if (--nativeTimer < 0) enter(ROUTINE_WAIT_FOR_CHILD, 0);
            return;
        }
        if (openingChildrenSpawned && !drillChildSpawned && !childReady) {
            drillChildSpawned = true;
            spawnChild(() -> new MgzEndBossKnuxDrillChild(this));
        }
        if (state.defeated) {
            updateDefeat();
            return;
        }
        switch (nativeRoutine) {
            case ROUTINE_WAIT_FOR_CHILD -> {
                if (childReady) {
                    enter(ROUTINE_FIRST_WAIT, FIRST_WAIT_TIMER);
                }
            }
            case ROUTINE_FIRST_WAIT -> waitThen(ROUTINE_FIRST_DROP, FIRST_DROP_TIMER);
            case ROUTINE_FIRST_DROP -> moveAndWait(0x28, ROUTINE_SECOND_WAIT, SECOND_WAIT_TIMER);
            case ROUTINE_SECOND_WAIT -> waitThen(ROUTINE_SECOND_DROP, SECOND_DROP_TIMER);
            case ROUTINE_SECOND_DROP -> moveAndWait(0x48, ROUTINE_RESET, RESET_TIMER);
            case ROUTINE_RESET -> {
                if (--nativeTimer < 0) {
                    state.x = RESET_X;
                    state.y = RESET_Y;
                    state.xFixed = state.x << 16;
                    state.yFixed = state.y << 16;
                    childReady = false;
                    enter(ROUTINE_WAIT_FOR_CHILD, INITIAL_TIMER);
                }
            }
            default -> throw new IllegalStateException("Unknown MGZ Knuckles boss routine " + nativeRoutine);
        }
    }

    private void waitThen(int nextRoutine, int nextTimer) {
        if (--nativeTimer < 0) {
            enter(nextRoutine, nextTimer);
        }
    }

    private void moveAndWait(int collapseAt, int nextRoutine, int nextTimer) {
        if (nativeTimer == collapseAt) {
            collapseEmissionCount++;
            services().playSfx(Sonic3kSfx.COLLAPSE.id);
            int cameraY = services().camera() == null ? state.y : services().camera().getY();
            boolean highBand = state.y > cameraY + 0x70;
            int emitterY = cameraY + (highBand ? 0xC8 : 0x18);
            spawnChild(() -> new MgzEndBossKnuxCollapseEmitter(state.x, emitterY, highBand));
        }
        state.xFixed += state.xVel << 8;
        state.yFixed += state.yVel << 8;
        state.updatePositionFromFixed();
        waitThen(nextRoutine, nextTimer);
    }

    private void enter(int routine, int timer) {
        nativeRoutine = routine;
        state.routine = routine;
        nativeTimer = timer;
        MotionSeed queuedSeed = routine == ROUTINE_FIRST_DROP ? pendingFirstMotionSeed
                : routine == ROUTINE_SECOND_DROP ? pendingSecondMotionSeed : null;
        if (queuedSeed != null) {
            MotionSeed seed = queuedSeed;
            int offsetIndex = Math.floorMod(seed.tableOffset() >> 1, DROP_OFFSETS.length);
            int velocityIndex = Math.floorMod(seed.angle() >> 1, FULL_VELOCITIES.length);
            boolean flip = offsetIndex == 1 || offsetIndex == 2 || offsetIndex == 4 || offsetIndex == 5;
            state.x = seed.baseX() + DROP_OFFSETS[offsetIndex][0];
            state.y = seed.baseY() + DROP_OFFSETS[offsetIndex][1];
            state.xFixed = state.x << 16; state.yFixed = state.y << 16;
            state.xVel = FULL_VELOCITIES[velocityIndex][0] >> 1;
            state.yVel = FULL_VELOCITIES[velocityIndex][1] >> 1;
            if (flip) state.xVel = -state.xVel;
            if (routine == ROUTINE_FIRST_DROP) pendingFirstMotionSeed = null;
            else pendingSecondMotionSeed = null;
        }
    }

    /** ROM child flag $38 bit 2, raised by the drill child after its opening motion. */
    public void signalDrillChildReady() {
        childReady = true;
    }

    /**
     * Consumes the semantic equivalent of the drill-child-owned ROM
     * {@code _unkFA82/_unkFA8A} four-word seed before loc_6D51A begins a drop.
     * The event owner supplies the four native words; this object resolves
     * {@code byte_6D588}, {@code byte_6D5A0}, and {@code word_6D34E} locally,
     * without global scratch RAM or route/frame branches.
     */
    void beginFirstDrop(MotionSeed fa82Seed) { pendingFirstMotionSeed = fa82Seed; }
    void beginSecondDrop(MotionSeed fa8aSeed) { pendingSecondMotionSeed = fa8aSeed; }

    /** Exact semantic publisher for {@code sub_6D42E -> _unkFA82}. */
    MotionSeed generateFa82MotionSeed() {
        int random = services().rng().nextRaw();
        int displacement = (random & 0xFF) - 0x80;
        int selector = random & 1;
        if (displacement < 0) selector += 2;
        int tableOffset = selector << 1;
        int[] angles = {4, 2, 4, 2, 0x0C, 0x0A, 0x0C, 0x0A};
        int cameraX = services().camera().getX() & 0xFFFF;
        int cameraY = services().camera().getY() & 0xFFFF;
        fa82MotionSeed = new MotionSeed(tableOffset, angles[selector],
                cameraX + 0xA8 + displacement, cameraY + 8);
        return fa82MotionSeed;
    }

    /** Exact semantic publisher for {@code sub_6D4DC -> _unkFA8A}. */
    MotionSeed generateFa8aMotionSeed() {
        if (fa82MotionSeed == null) {
            throw new IllegalStateException("FA8A cannot be published before the drill child publishes FA82");
        }
        int random = services().rng().nextRaw();
        int displacement = random & 0x7F;
        int selector = (random & 1) + 4;
        int[] selectorDelta = {2, 0, 0, 2};
        int priorSelector = Math.floorMod(fa82MotionSeed.tableOffset() >> 1, 4);
        int delta = selectorDelta[priorSelector];
        if (delta != 0) displacement = -displacement;
        selector += delta;
        int[] angles = {4, 2, 4, 2, 0x0C, 0x0A, 0x0C, 0x0A};
        int cameraX = services().camera().getX() & 0xFFFF;
        return new MotionSeed(selector << 1, angles[selector],
                cameraX + 0xA8 + displacement, 0x00F8);
    }

    public int getNativeRoutineForTesting() { return nativeRoutine; }
    public int getNativeTimerForTesting() { return nativeTimer; }
    public int getCollapseEmissionCountForTesting() { return collapseEmissionCount; }
    public int getNativeXVelocityForTesting() { return state.xVel; }
    public int getNativeYVelocityForTesting() { return state.yVel; }
    public static int getCapsuleX() { return 0x3F40; }
    public static int getCapsuleY() { return 0x00B0; }

    @Override
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        if (state.invulnerable || state.defeated) {
            return;
        }
        state.hitCount--;
        state.invulnerable = true;
        state.invulnerabilityTimer = INVULNERABLE_FRAMES;
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
        if (state.hitCount <= 0) {
            state.hitCount = 0;
            state.defeated = true;
            defeatTimer = DEFEAT_EXPLOSION_FRAMES;
            if (services().gameState() != null) {
                services().gameState().addScore(1000);
            }
            services().playSfx(Sonic3kSfx.EXPLODE.id);
            spawnChild(() -> new S3kBossExplosionChild(state.x, state.y));
        }
    }

    private void updateDefeat() {
        if (!defeatFadeStarted) {
            defeatFadeStarted = true;
            spawnFreeChild(() -> new SongFadeTransitionInstance(2 * 60, Sonic3kMusic.MGZ2.id));
            defeatTimer = (2 * 60) - 1;
            return;
        }
        if (!defeatLocEntered) {
            if (defeatTimer-- > 0) return;
            defeatLocEntered = true;
            defeatTimer = DEFEAT_EXPLOSION_FRAMES;
            for (int i = 0; i < 3; i++) {
                int debrisIndex = i;
                spawnChild(() -> new MgzEndBossKnuxDefeatPart(new ObjectSpawn(
                        state.x, state.y, 0, debrisIndex << 1, 0, false, 0)));
            }
            return;
        }
        if (defeatTimer > 0) {
            defeatTimer--;
            if ((defeatTimer & 7) == 0) {
                services().playSfx(Sonic3kSfx.EXPLODE.id);
                spawnChild(() -> new S3kBossExplosionChild(state.x, state.y));
            }
            return;
        }
        if (!capsuleSpawned) {
            capsuleSpawned = true;
            services().gameState().setCurrentBossId(0);
            spawnFreeChild(() -> new MgzEndBossKnuxEggCapsuleInstance(this, new ObjectSpawn(
                    getCapsuleX(), getCapsuleY(), Sonic3kObjectIds.EGG_CAPSULE, 0, 0, false, 0)));
            if (services().camera() != null) {
                services().camera().setMinX(services().camera().getX());
                int targetMaxX = (services().camera().getMaxX() & 0xFFFF) + 0x118;
                spawnChild(() -> new HczEndBossGradualMaxXExtender(state.x, state.y, targetMaxX));
            }
            return;
        }
        if (resultsComplete) {
            if (!postResultsContinuation) beginPostResultsContinuation();
            updatePostResultsContinuation();
        } else if (services().camera() != null) {
            services().camera().setMinX(services().camera().getX());
        }
    }

    private void beginPostResultsContinuation() {
        postResultsContinuation = true;
        services().playMusic(Sonic3kMusic.MGZ2.id);
        var camera = services().camera();
        if (camera != null) {
            camera.setScrollLocked(true);
            camera.setMaxX((short) (camera.getMaxX() + 0x30));
        }
        lockPlayerAfterResults(services().playerQuery().mainPlayerOrNull());
        PlayableEntity main = services().playerQuery().mainPlayerOrNull();
        postResultsLastX = main == null ? 0 : main.getCentreX() & 0xFFFF;
    }

    private void updatePostResultsContinuation() {
        PlayableEntity main = services().playerQuery().mainPlayerOrNull();
        publishPostResultsInput(main);
        int cameraX = services().camera() == null ? 0 : services().camera().getX() & 0xFFFF;
        if (main != null && (main.getCentreX() & 0xFFFF) < cameraX + 0x150) return;
        completionReady = true;
        if (services().camera() != null) services().camera().setScrollLocked(false);
        ObjectLifetimeOps.destroyLatched(this);
    }

    /** ROM loc_86334: locked-player RIGHT, with an A+RIGHT pulse after pushing. */
    private void publishPostResultsInput(PlayableEntity player) {
        if (!(player instanceof AbstractPlayableSprite sprite)) return;
        int input = AbstractPlayableSprite.INPUT_RIGHT;
        int currentX = sprite.getCentreX() & 0xFFFF;
        if (currentX == postResultsLastX) postResultsStallTimer++;
        else postResultsStallTimer = 0;
        postResultsLastX = currentX;
        if (postResultsStallTimer >= 0x10) {
            postResultsInputTimer = 0x1F;
            postResultsStallTimer = 0;
        }
        if (postResultsInputTimer > 0) {
            postResultsInputTimer--;
            input |= AbstractPlayableSprite.INPUT_JUMP;
        }
        if (sprite.getPushing()) {
            postResultsInputTimer = 0x1F;
            input |= AbstractPlayableSprite.INPUT_JUMP;
        }
        sprite.setForcedInputMask(input);
        sprite.setLogicalInputState(false, false, false, true,
                (input & AbstractPlayableSprite.INPUT_JUMP) != 0);
    }

    private static void lockPlayerAfterResults(PlayableEntity player) {
        if (player instanceof AbstractPlayableSprite sprite) {
            ObjectControlState.none().applyTo(sprite);
            sprite.setForcedInputMask(0);
            sprite.setControlLocked(true);
        }
    }

    private void restorePlayerControlAfterResults() {
        restorePlayerControl(services().playerQuery().mainPlayerOrNull());
        restorePlayerControl(services().playerQuery().nativeP2OrNull());
    }

    private static void restorePlayerControl(PlayableEntity player) {
        if (player instanceof AbstractPlayableSprite sprite) {
            ObjectControlState.none().applyTo(sprite);
            sprite.setControlLocked(false);
            sprite.setForcedInputMask(0);
        }
    }

    /** Event/capsule handoff corresponding to the ROM's cleared _unkFAA8 flag. */
    public void signalResultsComplete() {
        resultsComplete = true;
    }

    public boolean isCapsuleSpawnedForTesting() { return capsuleSpawned; }
    public boolean isCompletionReady() { return completionReady; }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return defeatLocEntered ? 4 : 6;
    }
}
