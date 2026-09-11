package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectSnapshot;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Buzzer (0x4B) - Flying wasp Badnik from Emerald Hill Zone.
 *
 * <p>ROM reference: {@code Obj4B} in {@code docs/s2disasm/s2.asm}.
 * The parent body (routine 2) patrols horizontally, checks Sonic/Tails on
 * alternating VBlank frames, and fires a stinger via {@code AllocateObjectAfterCurrent}.
 * The init routine also spawns an exhaust flame child (routine 4) in the next slot.
 */
public class BuzzerBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x0A; // collision_flags = $0A
    private static final int X_VEL = 0x100;
    private static final int MOVE_TIMER_INIT = 0x100;
    private static final int TURN_DELAY = 0x1E;
    private static final int TURN_AROUND_TRIGGER = 0x0F;
    private static final int SHOOT_DISTANCE_MIN = 0x28;
    private static final int SHOOT_DISTANCE_MAX = 0x30;
    private static final int SHOT_TIMER_INIT = 0x32;
    private static final int SHOT_FIRE_FRAME = 0x14;
    private static final int PROJECTILE_X_OFFSET = 0x0D;
    private static final int PROJECTILE_Y_OFFSET = 0x18;
    private static final int PROJECTILE_X_VEL = 0x180;
    private static final int PROJECTILE_Y_VEL = 0x180;

    private enum State {
        ROAMING,
        SHOOTING
    }

    private State state;
    private int moveTimer;
    private int turnDelay;
    private int shotTimer;
    private boolean shootingDisabled;
    private boolean initPending;

    public BuzzerBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Buzzer", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.facingLeft = (spawn.renderFlags() & 0x01) == 0;
        this.state = State.ROAMING;
        this.moveTimer = 0;
        this.turnDelay = 0;
        this.shotTimer = 0;
        this.shootingDisabled = false;
        this.initPending = true;
    }

    @Override
    public BuzzerBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new BuzzerBadnikInstance(ctx.spawn());
    }

    @Override
    public void hydrateFromRomSnapshot(RomObjectSnapshot snapshot) {
        super.hydrateFromRomSnapshot(snapshot);

        int routine = snapshot.routine() & 0xFF;
        this.initPending = routine < 0x02;
        this.state = (snapshot.routineSecondary() & 0xFF) == 0x02
                ? State.SHOOTING
                : State.ROAMING;
        this.moveTimer = snapshot.wordAt(0x2E) & 0xFFFF;
        this.turnDelay = snapshot.signedWordAt(0x30);
        this.shootingDisabled = (snapshot.byteAt(0x32) & 0xFF) != 0;
        this.shotTimer = snapshot.signedWordAt(0x34);
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (initPending) {
            runInitRoutine();
            return;
        }

        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite
                ? sprite
                : null;
        switch (state) {
            case ROAMING -> updateRoaming(vIntRunCount, player);
            case SHOOTING -> updateShooting();
        }
    }

    private void runInitRoutine() {
        initPending = false;
        state = State.ROAMING;
        moveTimer = MOVE_TIMER_INIT;
        turnDelay = 0;
        shotTimer = 0;
        shootingDisabled = false;
        xVelocity = facingLeft ? -X_VEL : X_VEL;

        spawnChild(() -> new BuzzerFlameChild(buildSpawnAt(currentX, currentY), this));
    }

    private void updateRoaming(int vIntRunCount, AbstractPlayableSprite player) {
        checkPlayerForShooting(vIntRunCount, player);

        turnDelay--;
        int delay = turnDelay;
        if (delay == TURN_AROUND_TRIGGER) {
            shootingDisabled = false;
            xVelocity = -xVelocity;
            facingLeft = !facingLeft;
            moveTimer = MOVE_TIMER_INIT;
            return;
        }

        if (delay < 0) {
            moveTimer--;
            if (moveTimer > 0) {
                currentX += (xVelocity >> 8);
            } else {
                turnDelay = TURN_DELAY;
            }
        }
    }

    private void checkPlayerForShooting(int vIntRunCount, AbstractPlayableSprite mainPlayer) {
        if (shootingDisabled) {
            return;
        }

        AbstractPlayableSprite target = selectTargetPlayer(vIntRunCount, mainPlayer);
        if (target == null) {
            return;
        }

        int distance = currentX - target.getCentreX();
        int absDistance = Math.abs(distance);
        if (absDistance < SHOOT_DISTANCE_MIN || absDistance > SHOOT_DISTANCE_MAX) {
            return;
        }

        boolean playerIsLeft = distance >= 0;
        if (playerIsLeft != facingLeft) {
            return;
        }

        shootingDisabled = true;
        state = State.SHOOTING;
        shotTimer = SHOT_TIMER_INIT;
    }

    private AbstractPlayableSprite selectTargetPlayer(int vIntRunCount, AbstractPlayableSprite mainPlayer) {
        if ((vIntRunCount & 1) == 0) {
            return mainPlayer;
        }

        if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick) {
            return sidekick;
        }
        return mainPlayer;
    }

    private void updateShooting() {
        int timer = shotTimer - 1;
        if (timer < 0) {
            state = State.ROAMING;
            shotTimer = timer;
            return;
        }

        shotTimer = timer;
        if (timer == SHOT_FIRE_FRAME) {
            fireProjectile();
        }
    }

    private void fireProjectile() {
        int xOffset = facingLeft ? PROJECTILE_X_OFFSET : -PROJECTILE_X_OFFSET;
        int xVel = facingLeft ? -PROJECTILE_X_VEL : PROJECTILE_X_VEL;

        spawnChild(() -> new BadnikProjectileInstance(
                buildSpawnAt(currentX + xOffset, currentY + PROJECTILE_Y_OFFSET),
                BadnikProjectileInstance.ProjectileType.BUZZER_STINGER,
                currentX + xOffset,
                currentY + PROJECTILE_Y_OFFSET,
                xVel,
                PROJECTILE_Y_VEL,
                false,
                !facingLeft));
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        animFrame = state == State.SHOOTING ? 1 : 0;
    }

    @Override
    public PerObjectRewindSnapshot captureRewindState() {
        PerObjectRewindSnapshot base = super.captureRewindState();
        return base.withBadnikSubclassExtra(new PerObjectRewindSnapshot.BuzzerRewindExtra(
                state.ordinal(),
                moveTimer,
                turnDelay,
                shotTimer,
                shootingDisabled,
                initPending));
    }

    @Override
    public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
        super.restoreRewindState(snapshot);
        if (snapshot.badnikSubclassExtra() instanceof PerObjectRewindSnapshot.BuzzerRewindExtra extra) {
            state = State.values()[extra.stateOrdinal()];
            moveTimer = extra.moveTimer();
            turnDelay = extra.turnDelay();
            shotTimer = extra.shotTimer();
            shootingDisabled = extra.shootingDisabled();
            initPending = extra.initPending();
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.BUZZER);
        if (renderer == null) {
            return;
        }

        renderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false);
    }

    private static final class BuzzerFlameChild extends AbstractObjectInstance implements RewindRecreatable {
        private final BuzzerBadnikInstance parent;
        private int parentSlotIndex;
        private int currentX;
        private int currentY;
        private boolean facingLeft;
        private int animFrame;

        private BuzzerFlameChild(ObjectSpawn spawn, BuzzerBadnikInstance parent) {
            super(spawn, "Buzzer Flame");
            this.parent = parent;
            this.parentSlotIndex = parent.getSlotIndex();
            this.currentX = parent.currentX;
            this.currentY = parent.currentY;
            this.facingLeft = parent.facingLeft;
            this.animFrame = 3;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
                return null;
            }
            ObjectManager objectManager = ctx.objectServices().objectManager();
            if (objectManager == null) {
                return null;
            }
            BuzzerBadnikInstance liveParent = null;
            if (ctx.state() != null
                    && ctx.state().objectSubclassExtra()
                    instanceof PerObjectRewindSnapshot.BuzzerFlameRewindExtra extra) {
                liveParent = findLiveParentBySlotForRewind(objectManager, extra.parentSlotIndex());
            }
            if (liveParent == null) {
                liveParent = findNearestLiveParentForRewind(objectManager, ctx.spawn());
            }
            return liveParent == null ? null : new BuzzerFlameChild(ctx.spawn(), liveParent);
        }

        private static BuzzerBadnikInstance findLiveParentBySlotForRewind(
                ObjectManager objectManager,
                int parentSlotIndex) {
            for (ObjectInstance instance : objectManager.getActiveObjects()) {
                if (instance instanceof BuzzerBadnikInstance buzzer
                        && !buzzer.isDestroyed()
                        && buzzer.getSlotIndex() == parentSlotIndex) {
                    return buzzer;
                }
            }
            return null;
        }

        private static BuzzerBadnikInstance findNearestLiveParentForRewind(
                ObjectManager objectManager,
                ObjectSpawn spawn) {
            if (spawn == null) {
                return null;
            }
            BuzzerBadnikInstance best = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance instance : objectManager.getActiveObjects()) {
                if (!(instance instanceof BuzzerBadnikInstance buzzer) || buzzer.isDestroyed()) {
                    continue;
                }
                long dx = buzzer.getX() - spawn.x();
                long dy = buzzer.getY() - spawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = buzzer;
                }
            }
            return best;
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
        public void update(int vIntRunCount, PlayableEntity player) {
            ObjectInstance slotOccupant = findParentSlotOccupant();
            if (slotOccupant == null) {
                setDestroyed(true);
                return;
            }

            if (slotOccupant == parent && parent.turnDelay < 0) {
                currentX = parent.currentX;
                currentY = parent.currentY;
                facingLeft = parent.facingLeft;
            }

            animFrame = 3 + ((vIntRunCount / 3) & 1);
            updateDynamicSpawn(currentX, currentY);
        }

        private ObjectInstance findParentSlotOccupant() {
            for (ObjectInstance instance : services().objectManager().getActiveObjects()) {
                if (!(instance instanceof AbstractObjectInstance aoi)) {
                    continue;
                }
                if (aoi == this || aoi.isDestroyed() || aoi.getSlotIndex() != parentSlotIndex) {
                    continue;
                }
                return aoi;
            }
            return null;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(4);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }

            PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.BUZZER);
            if (renderer == null) {
                return;
            }

            renderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false);
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            return super.captureRewindState().withObjectSubclassExtra(
                    new PerObjectRewindSnapshot.BuzzerFlameRewindExtra(
                            parentSlotIndex,
                            currentX,
                            currentY,
                            facingLeft,
                            animFrame));
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
            super.restoreRewindState(snapshot);
            if (snapshot.objectSubclassExtra()
                    instanceof PerObjectRewindSnapshot.BuzzerFlameRewindExtra extra) {
                currentX = extra.currentX();
                currentY = extra.currentY();
                facingLeft = extra.facingLeft();
                animFrame = extra.animFrame();
                parentSlotIndex = extra.parentSlotIndex();
            }
        }
    }
}
