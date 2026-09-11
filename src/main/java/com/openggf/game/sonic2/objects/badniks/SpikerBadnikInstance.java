package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.AnimationTimer;

import java.util.List;

/**
 * Spiker (0x92) - drill badnik from HTZ.
 * Moves horizontally, pauses, and throws a drill once when the player is near.
 * Based on disassembly Obj92.
 */
public class SpikerBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x12; // From Obj92_SubObjData

    private static final int MOVE_TIMER_INIT = 0x40;   // objoff_2A = $40
    private static final int PAUSE_TIMER_INIT = 0x10;  // objoff_2A = $10
    private static final int X_VELOCITY = 0x80;        // x_vel = $80 (8.8 fixed)
    private static final int THROW_TIMER_INIT = 0x10;  // objoff_2E = $10
    private static final int THROW_TRIGGER = 0x08;     // Spawn drill when timer == 8

    // Detection ranges (Obj_GetOrientationToPlayer + bounds checks)
    private static final int DETECT_RANGE_X = 0x20; // +/- 32px
    private static final int DETECT_RANGE_Y = 0x80; // +/- 128px

    private enum State {
        MOVE,       // Routine 2
        PAUSE,      // Routine 4
        THROW_PREP  // Routine 6
    }

    private State state;
    private State returnState;
    private int moveTimer;
    private int pauseTimer;
    private int throwTimer;
    private final SubpixelMotion.State motionState;
    private boolean hasThrown;
    private boolean useAttackAnim;
    private boolean animateThisFrame;
    private boolean xFlipFlag;
    private final AnimationTimer anim = new AnimationTimer(9, 2);
    private boolean yFlipFlag;

    public SpikerBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Spiker", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xVelocity = X_VELOCITY;
        this.moveTimer = MOVE_TIMER_INIT;
        this.pauseTimer = 0;
        this.throwTimer = 0;
        this.state = State.MOVE;
        this.returnState = State.MOVE;
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, X_VELOCITY, 0);
        this.hasThrown = false;
        this.useAttackAnim = false;

        boolean initialXFlip = (spawn.renderFlags() & 0x01) != 0;
        this.yFlipFlag = (spawn.renderFlags() & 0x02) != 0;
        // bchg #status.npc.x_flip,status(a0)
        this.xFlipFlag = !initialXFlip;
        this.facingLeft = !xFlipFlag;
    }

    @Override
    public SpikerBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SpikerBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        animateThisFrame = false;

        switch (state) {
            case MOVE -> updateMove(player);
            case PAUSE -> updatePause(player);
            case THROW_PREP -> updateThrowPrep();
        }
    }

    private void updateMove(AbstractPlayableSprite player) {
        boolean triggered = checkForThrow(player);

        if (!triggered) {
            moveTimer--;
            if (moveTimer < 0) {
                state = State.PAUSE;
                pauseTimer = PAUSE_TIMER_INIT;
                return;
            }
        }

        motionState.x = currentX;
        motionState.xVel = xVelocity;
        SubpixelMotion.moveX(motionState);
        currentX = motionState.x;

        animateThisFrame = true;
    }

    private void updatePause(AbstractPlayableSprite player) {
        if (checkForThrow(player)) {
            return;
        }

        pauseTimer--;
        if (pauseTimer < 0) {
            state = State.MOVE;
            moveTimer = MOVE_TIMER_INIT;
            xVelocity = -xVelocity;
            xFlipFlag = !xFlipFlag;
            facingLeft = !facingLeft;
        }
    }

    private void updateThrowPrep() {
        if (throwTimer == THROW_TRIGGER) {
            spawnDrill();
            hasThrown = true;
            useAttackAnim = true;
            animFrame = 2;
            anim.reset();
            state = returnState;
            return;
        }

        throwTimer--;
    }

    private boolean checkForThrow(AbstractPlayableSprite player) {
        AbstractPlayableSprite target = closestNativePlayer(player);
        if (hasThrown || target == null || !isOnScreenX()) {
            return false;
        }

        int dx = currentX - target.getCentreX();
        int dy = currentY - target.getCentreY();

        int adjustedDx = dx + DETECT_RANGE_X;
        if (adjustedDx < 0 || adjustedDx >= (DETECT_RANGE_X * 2)) {
            return false;
        }

        int adjustedDy = dy + DETECT_RANGE_Y;
        if (adjustedDy < 0 || adjustedDy >= (DETECT_RANGE_Y * 2)) {
            return false;
        }

        returnState = state;
        state = State.THROW_PREP;
        throwTimer = THROW_TIMER_INIT;
        return true;
    }

    private AbstractPlayableSprite closestNativePlayer(AbstractPlayableSprite fallbackPlayer) {
        var svc = tryServices();
        if (svc == null) {
            return fallbackPlayer;
        }
        var nearest = svc.playerQuery().nearestByRomX(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2, currentX);
        return nearest != null && nearest.player() instanceof AbstractPlayableSprite sprite
                ? sprite
                : fallbackPlayer;
    }

    private void spawnDrill() {
        int renderFlags = spawn.renderFlags();
        renderFlags = (renderFlags & ~0x01) | (xFlipFlag ? 0x01 : 0);
        ObjectSpawn drillSpawn = new ObjectSpawn(
                currentX,
                currentY,
                Sonic2ObjectIds.SPIKER_DRILL,
                spawn.subtype(),
                renderFlags,
                false,
                spawn.rawYWord());
        spawnFreeChild(() -> new SpikerDrillObjectInstance(
                drillSpawn,
                currentX,
                currentY,
                xFlipFlag,
                yFlipFlag));
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        if (!animateThisFrame) {
            return;
        }

        int baseFrame = useAttackAnim ? 2 : 0;
        if (animFrame < baseFrame || animFrame > baseFrame + 1) {
            animFrame = baseFrame;
            anim.reset();
        }

        anim.tick();
        animFrame = baseFrame + anim.getFrame();
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
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

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.SPIKER);
        if (renderer == null) return;

        renderer.drawFrameIndex(animFrame, currentX, currentY, xFlipFlag, yFlipFlag);
    }
}
