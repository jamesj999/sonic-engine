package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Coconuts (0x9D) - Monkey Badnik from EHZ.
 * Climbs up and down a tree and throws coconut projectiles at the player.
 */
public class CoconutsBadnikInstance extends AbstractBadnikInstance {
    private static final int COLLISION_SIZE_INDEX = 0x09;
    private static final int IDLE_TIMER_INIT = 0x10;
    private static final int ATTACK_TIMER_RESET = 0x20;
    private static final int THROW_TIMER_INIT = 0x08;
    private static final int THROW_RANGE = 0x60;
    private static final int THROW_X_OFFSET = 0x0B;
    private static final int THROW_Y_OFFSET = -0x0D;
    private static final int THROW_X_VEL = 0x100;
    private static final int THROW_Y_VEL = -0x100;
    private static final int CLIMB_ANIM_SPEED = 5;
    private static final int[][] CLIMB_DATA = {
            { -1, 0x20 },
            { 1, 0x18 },
            { -1, 0x10 },
            { 1, 0x28 },
            { -1, 0x20 },
            { 1, 0x10 }
    };

    private enum State {
        IDLE,
        CLIMBING,
        THROWING
    }

    private enum ThrowState {
        HAND_RAISED,
        HAND_LOWERED
    }

    private int timer;
    private int climbTableIndex;
    private int attackTimer;
    private int yVelocity;
    private State state;
    private ThrowState throwState;

    public CoconutsBadnikInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, levelManager, "Coconuts");
        this.currentY = spawn.y();
        this.currentX = spawn.x();
        this.timer = IDLE_TIMER_INIT;
        this.climbTableIndex = 0;
        this.attackTimer = 0;
        this.yVelocity = 0;
        this.state = State.IDLE;
        this.throwState = ThrowState.HAND_RAISED;
    }

    @Override
    protected void updateMovement(int frameCounter, AbstractPlayableSprite player) {
        currentX = spawn.x();
        switch (state) {
            case IDLE -> updateIdle(player);
            case CLIMBING -> updateClimbing();
            case THROWING -> updateThrowing();
        }
    }

    private void updateIdle(AbstractPlayableSprite player) {
        if (player != null) {
            facingLeft = player.getCentreX() < currentX;
            int distance = Math.abs(player.getCentreX() - currentX);
            if (distance < THROW_RANGE) {
                if (attackTimer == 0) {
                    startThrowing();
                    return;
                }
                attackTimer--;
            }
        }

        timer--;
        if (timer < 0) {
            state = State.CLIMBING;
            setClimbingDirection();
        }
    }

    private void updateClimbing() {
        timer--;
        if (timer <= 0) {
            state = State.IDLE;
            timer = IDLE_TIMER_INIT;
            return;
        }

        currentY += (yVelocity >> 8);
    }

    private void updateThrowing() {
        timer--;
        if (timer >= 0) {
            return;
        }

        if (throwState == ThrowState.HAND_RAISED) {
            throwState = ThrowState.HAND_LOWERED;
            timer = THROW_TIMER_INIT;
            animFrame = 2;
            throwCoconut();
            return;
        }

        throwState = ThrowState.HAND_RAISED;
        state = State.CLIMBING;
        setClimbingDirection();
    }

    private void startThrowing() {
        state = State.THROWING;
        throwState = ThrowState.HAND_RAISED;
        animFrame = 1;
        timer = THROW_TIMER_INIT;
        attackTimer = ATTACK_TIMER_RESET;
    }

    private void setClimbingDirection() {
        if (climbTableIndex >= CLIMB_DATA.length) {
            climbTableIndex = 0;
        }
        int[] entry = CLIMB_DATA[climbTableIndex++];
        yVelocity = entry[0] << 8;
        timer = entry[1];
    }

    private void throwCoconut() {
        int xOffset;
        int xVel;
        if (facingLeft) {
            xOffset = THROW_X_OFFSET;
            xVel = -THROW_X_VEL;
        } else {
            xOffset = -THROW_X_OFFSET;
            xVel = THROW_X_VEL;
        }

        BadnikProjectileInstance projectile = new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.COCONUT,
                currentX + xOffset,
                currentY + THROW_Y_OFFSET,
                xVel,
                THROW_Y_VEL,
                true,
                !facingLeft);

        LevelManager.getInstance().getObjectManager().addDynamicObject(projectile);
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        if (state == State.THROWING) {
            animFrame = (throwState == ThrowState.HAND_RAISED) ? 1 : 2;
            return;
        }
        if (state == State.CLIMBING) {
            animFrame = ((frameCounter / CLIMB_ANIM_SPEED) & 1);
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.COCONUTS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = !facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
