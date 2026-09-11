package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.GameRng;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * S3K S3KL Obj $C1 - Corkey (LBZ).
 *
 * <p>ROM reference: {@code Obj_Corkey} at {@code sonic3k.asm:191738}. The
 * parent paces horizontally, raises status byte {@code $38} bit 1 when ready to
 * fire, and waits until the child nozzle clears that bit after the shot cycle.
 */
public final class CorkeyBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x0B; // ObjDat_Corkey collision_flags.
    private static final int PRIORITY_BUCKET = 5;         // ObjDat_Corkey priority $280.
    private static final int NOZZLE_Y_OFFSET = 0x0C;      // ChildObjDat_8C90E.
    private static final int WAIT_OFFSCREEN_HALF_SIZE = 0x20;

    private enum State {
        INIT,
        PATROL,
        WAIT_FOR_NOZZLE
    }

    private State state = State.INIT;
    private int movementStep;
    private int movementTimer;
    private int patrolTurnaroundTimer;
    private int patrolTurnaroundReset;
    private boolean firingLatch;
    private boolean waitingForOnscreen = true;
    private boolean placeholderRenderedOnscreen;

    public CorkeyBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Corkey", Sonic3kObjectArtKeys.CORKEY, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        this.mappingFrame = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // Obj_WaitOffscreen installs a $20-by-$20 placeholder. Render_Sprites
        // publishes its sign bit after Process_Sprites; the next dispatch only
        // restores Obj_Corkey and returns, so real initialization begins one
        // object pass later.
        if (waitingForOnscreen) {
            if (!placeholderRenderedOnscreen) {
                return;
            }
            waitingForOnscreen = false;
            placeholderRenderedOnscreen = false;
            return;
        }

        switch (state) {
            case INIT -> initialize();
            case PATROL -> updatePatrol();
            case WAIT_FOR_NOZZLE -> updateWaitForNozzle();
        }
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (waitingForOnscreen) {
            placeholderRenderedOnscreen = isWithinRenderSpriteBounds(
                    WAIT_OFFSCREEN_HALF_SIZE, WAIT_OFFSCREEN_HALF_SIZE);
        }
    }

    private void initialize() {
        // loc_8C746: render_flags bit 0 flips the one-pixel patrol direction.
        movementStep = facingLeft ? -1 : 1;
        patrolTurnaroundTimer = spawn.subtype();
        patrolTurnaroundReset = (spawn.subtype() << 1) & 0xFF;
        spawnChild(() -> new CorkeyNozzleChild(buildSpawnAt(currentX, currentY + NOZZLE_Y_OFFSET), this));
        resetMovementTimer();
        state = State.PATROL;
    }

    private void updatePatrol() {
        movementTimer--;
        if (movementTimer < 0) {
            state = State.WAIT_FOR_NOZZLE;
            firingLatch = true;
            return;
        }
        currentX += movementStep;
        updatePatrolTurnaround();
    }

    private void updatePatrolTurnaround() {
        patrolTurnaroundTimer--;
        if (patrolTurnaroundTimer >= 0) {
            return;
        }
        movementStep = -movementStep;
        patrolTurnaroundTimer = patrolTurnaroundReset;
    }

    private void updateWaitForNozzle() {
        if (!firingLatch) {
            state = State.PATROL;
            resetMovementTimer();
        }
    }

    private void resetMovementTimer() {
        int value = nextRandomWord() & 0x3F;
        if ((value & 0x30) == 0) {
            value |= 0x30;
        }
        movementTimer = value;
    }

    private int nextRandomWord() {
        ObjectServices svc = tryServices();
        GameRng rng = svc != null ? svc.rng() : null;
        return rng != null ? rng.nextWord() : 0x30;
    }

    private boolean consumeFiringLatch() {
        return firingLatch;
    }

    private void clearFiringLatch() {
        firingLatch = false;
    }

    int movementStepForTesting() {
        return movementStep;
    }

    int movementTimerForTesting() {
        return movementTimer;
    }

    boolean firingLatchForTesting() {
        return firingLatch;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s spawn=%04X step=%d move=%d turn=%d/%d fire=%s wait=%s/%s",
                state, spawn.x() & 0xFFFF, movementStep, movementTimer,
                patrolTurnaroundTimer, patrolTurnaroundReset, firingLatch,
                waitingForOnscreen, placeholderRenderedOnscreen);
    }

    // Public so cross-package rewind recreate can name the nozzle type for
    // relinking the fired shot's script on a held-rewind restore.
    public static final class CorkeyNozzleChild extends AbstractObjectInstance implements RewindRecreatable {
        private static final int PRIORITY_BUCKET = 5;       // word_8C900 priority $280.
        private static final int DEFAULT_FRAME = 1;         // word_8C900 mapping_frame.
        private static final int RETRACT_FRAME = 2;         // loc_8C88C.
        private static final int RETRACT_WAIT = 7;          // loc_8C88C: move.w #7,$2E.
        private static final int RAW_INITIAL_DELAY = 7;     // byte_8C92E[0].
        private static final int RAW_LOOP_COUNT = 0x10;     // byte_8C92E[1].
        private static final int[] RAW_FRAMES = {1, 3};     // byte_8C92E[2..3], $FC loops.

        private enum State {
            FOLLOW,
            FIRING,
            RETRACT_WAIT
        }

        private final transient CorkeyBadnikInstance parent;
        private State state = State.FOLLOW;
        private int currentX;
        private int currentY;
        private int mappingFrame = DEFAULT_FRAME;
        private int rawDelay;
        private int rawLoopCounter;
        private int rawFrameIndex;
        private int rawFrameTimer;
        private int retractTimer;

        CorkeyNozzleChild(ObjectSpawn spawn, CorkeyBadnikInstance parent) {
            super(spawn, "CorkeyNozzle");
            this.parent = parent;
            this.currentX = spawn.x();
            this.currentY = spawn.y();
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            CorkeyBadnikInstance liveParent = findNearestLiveParentForRewind(ctx);
            if (liveParent == null) {
                return null;
            }
            return new CorkeyNozzleChild(ctx.spawn(), liveParent);
        }

        private static CorkeyBadnikInstance findNearestLiveParentForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            CorkeyBadnikInstance best = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
                if (!(instance instanceof CorkeyBadnikInstance candidate) || candidate.isDestroyed()) {
                    continue;
                }
                if (spawn == null) {
                    return candidate;
                }
                long dx = candidate.getX() - spawn.x();
                long dy = candidate.getY() + NOZZLE_Y_OFFSET - spawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            return best;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed() || parent.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }

            switch (state) {
                case FOLLOW -> updateFollow();
                case FIRING -> updateFiring();
                case RETRACT_WAIT -> updateRetractWait();
            }
            updateDynamicSpawn(currentX, currentY);
        }

        private void updateFollow() {
            refreshPosition();
            if (!parent.consumeFiringLatch()) {
                return;
            }
            state = State.FIRING;
            rawDelay = RAW_INITIAL_DELAY;
            rawLoopCounter = 0;
            rawFrameIndex = 0;
            rawFrameTimer = 0;
        }

        private void updateFiring() {
            refreshPosition();
            if (advanceRawGetFaster()) {
                if (rawLoopCounter == 4) {
                    spawnShot(-4, 0x54, CorkeyShotChild.SCRIPT_LEFT);
                } else if (rawLoopCounter == 5) {
                    spawnShot(4, 0x54, CorkeyShotChild.SCRIPT_RIGHT);
                } else if (rawLoopCounter == 6) {
                    spawnShot(0, 0x54, CorkeyShotChild.SCRIPT_CENTER);
                }
            }
        }

        private boolean advanceRawGetFaster() {
            rawFrameTimer--;
            if (rawFrameTimer >= 0) {
                return false;
            }

            rawFrameIndex++;
            if (rawFrameIndex >= RAW_FRAMES.length) {
                rawFrameIndex = 0;
                if (rawDelay > 0) {
                    rawDelay--;
                } else {
                    rawLoopCounter++;
                    if (rawLoopCounter >= RAW_LOOP_COUNT) {
                        enterRetractWait();
                        return false;
                    }
                }
            }

            mappingFrame = RAW_FRAMES[rawFrameIndex];
            rawFrameTimer = rawDelay;
            return rawDelay == 0 && rawFrameIndex == 0;
        }

        private void enterRetractWait() {
            state = State.RETRACT_WAIT;
            mappingFrame = RETRACT_FRAME;
            retractTimer = RETRACT_WAIT;
        }

        private void updateRetractWait() {
            refreshPosition();
            retractTimer--;
            if (retractTimer >= 0) {
                return;
            }
            state = State.FOLLOW;
            mappingFrame = DEFAULT_FRAME;
            parent.clearFiringLatch();
        }

        private void spawnShot(int dx, int dy, int[] script) {
            int shotX = currentX + dx;
            int shotY = currentY + dy;
            spawnChild(() -> new CorkeyShotChild(buildSpawnAt(shotX, shotY), shotX, shotY, script));
        }

        private void refreshPosition() {
            currentX = parent.getX();
            currentY = parent.getY() + NOZZLE_Y_OFFSET;
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
            return PRIORITY_BUCKET;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = renderer();
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
            }
        }

        private PatternSpriteRenderer renderer() {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return null;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.CORKEY);
            return renderer != null && renderer.isReady() ? renderer : null;
        }
    }

    public static final class CorkeyShotChild extends AbstractObjectInstance
            implements TouchResponseProvider, SpawnRewindRecreatable {
        private static final int COLLISION_FLAGS = 0xA0; // word_8C906 collision_flags.
        private static final int PRIORITY_BUCKET = 5;    // word_8C906 priority $280.
        private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        private static final int[] SCRIPT_LEFT = {6, 0, 6, 0, 7, 4, 5, 0, -0x0C};
        private static final int[] SCRIPT_RIGHT = {5, 0, 5, 0, 7, 4, 6, 0, -0x0C};
        private static final int[] SCRIPT_CENTER = {
                4, 0, 5, 0, 7, 0,
                4, 0, 5, 0, 7, 0,
                4, 0, 5, 0, 7, 0,
                4, 0, 5, 0, 7, 0,
                4, 0, 5, 0, 7, 0,
                6, 3, -0x0C
        };

        // Un-final so the generic field capturer can reapply the captured value
        // after a rewind recreate. Generic recreate starts with the center script
        // placeholder and restore overwrites it with the exact captured int[].
        private int[] script;
        private int currentX;
        private int currentY;
        private int mappingFrame;
        private int rawFrameIndex;
        private int rawFrameTimer;
        private boolean initialized;

        public CorkeyShotChild(ObjectSpawn spawn, int x, int y, int[] script) {
            super(spawn, "CorkeyShot");
            this.currentX = x;
            this.currentY = y;
            this.script = script;
        }

        public CorkeyShotChild(ObjectSpawn spawn) {
            this(spawn, spawn.x(), spawn.y(), scriptForSpawn(spawn, null));
        }

        /**
         * Reconstructs the shot's fire script from its spawn position relative to
         * the live firing nozzle. {@link CorkeyNozzleChild#spawnShot} offsets the
         * shot x by {@code -4} (left), {@code +4} (right), or {@code 0} (center).
         * Used by rewind recreate to relink the correct variant; defaults to the
         * center script when the nozzle is unavailable.
         */
        public static int[] scriptForSpawn(ObjectSpawn spawn, CorkeyNozzleChild nozzle) {
            if (spawn != null && nozzle != null) {
                int dx = spawn.x() - nozzle.getX();
                if (dx == -4) {
                    return SCRIPT_LEFT;
                }
                if (dx == 4) {
                    return SCRIPT_RIGHT;
                }
            }
            return SCRIPT_CENTER;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed()) {
                return;
            }
            if (!initialized) {
                services().playSfx(Sonic3kSfx.LASER.id);
                initialized = true;
            }
            advanceRawMultiDelay();
            updateDynamicSpawn(currentX, currentY);
        }

        private void advanceRawMultiDelay() {
            rawFrameTimer--;
            if (rawFrameTimer >= 0) {
                return;
            }

            rawFrameIndex += 2;
            if (rawFrameIndex >= script.length || script[rawFrameIndex] < 0) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            mappingFrame = script[rawFrameIndex];
            rawFrameTimer = script[rawFrameIndex + 1];
        }

        @Override
        public int getCollisionFlags() {
            return COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return TOUCH_RESPONSE_PROFILE;
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
            return PRIORITY_BUCKET;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = renderer();
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
            }
        }

        private PatternSpriteRenderer renderer() {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return null;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.CORKEY);
            return renderer != null && renderer.isReady() ? renderer : null;
        }
    }
}
