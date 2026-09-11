package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Sol (0x95) - fireball-throwing badnik from HTZ.
 * Moves horizontally while orbiting four fireballs; fires each when its
 * orbit angle reaches 0x40 during the body animation frame 2.
 * Based on disassembly Obj95.
 */
public class SolBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    private enum State {
        WAIT_FOR_PLAYER, // Routine 2
        AFTER_FIRE       // Routine 4
    }

    private static final int COLLISION_SIZE_INDEX = 0x0B;
    private static final int X_VELOCITY = 0x40; // x_vel = -$40 (8.8 fixed)
    private static final int DETECT_RANGE_X = 0xA0;
    private static final int DETECT_RANGE_Y = 0x50;

    private static final SpriteAnimationSet BODY_ANIMATIONS = createBodyAnimations();
    private static final SpriteAnimationSet FIREBALL_ANIMATIONS = createFireballAnimations();

    private boolean xFlip;
    private int orbitDirection;
    private final ObjectAnimationState bodyAnimation;
    private final ObjectAnimationState afterAnimation;
    private final List<SolFireballObjectInstance> fireballs = new ArrayList<>();
    private int fireballsRemaining;
    private final SubpixelMotion.State motionState;
    private State state;
    private boolean fireballsSpawned;

    public SolBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Sol", Sonic2BadnikConfig.DESTRUCTION);
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.orbitDirection = xFlip ? -1 : 1;
        this.xVelocity = xFlip ? X_VELOCITY : -X_VELOCITY;
        this.facingLeft = !xFlip;
        this.bodyAnimation = new ObjectAnimationState(BODY_ANIMATIONS, 0, 0);
        this.afterAnimation = new ObjectAnimationState(FIREBALL_ANIMATIONS, 0, 3);
        this.state = resolveInitialState(spawn.subtype());
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
    }

    @Override
    public SolBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SolBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        ensureFireballsSpawned();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case WAIT_FOR_PLAYER -> {
                checkForPlayer(player);
                applyObjectMove();
            }
            case AFTER_FIRE -> applyObjectMove();
        }
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // In AFTER_FIRE state, use fast flashing animation (duration 5 = ~5 Hz)
        // In WAIT_FOR_PLAYER state, use slower body animation (duration 15)
        ObjectAnimationState animationState = (state == State.AFTER_FIRE) ? afterAnimation : bodyAnimation;
        animationState.update();
        // Mask to 0-3: Frame 4 becomes frame 0 (body), matching original ROM behavior.
        // This creates a "flash" effect alternating between fireball (frame 3, palette 1)
        // and body (frame 0, palette 0).
        animFrame = animationState.getMappingFrame() & 0x03;
    }

    private void checkForPlayer(AbstractPlayableSprite player) {
        if (player == null || player.isDebugMode()) {
            return;
        }

        int dx = player.getCentreX() - currentX;
        if (Math.abs(dx) >= DETECT_RANGE_X) {
            return;
        }

        int dy = player.getCentreY() - currentY;
        if (Math.abs(dy) >= DETECT_RANGE_Y) {
            return;
        }

        bodyAnimation.setAnimId(1);
    }

    private void applyObjectMove() {
        motionState.x = currentX;
        motionState.xVel = xVelocity;
        SubpixelMotion.moveX(motionState);
        currentX = motionState.x;
    }

    private void ensureFireballsSpawned() {
        if (fireballsSpawned || services().objectManager() == null) {
            return;
        }
        fireballsSpawned = true;
        int[] angles = { 0x00, 0x40, 0x80, 0xC0 };
        for (int angle : angles) {
            SolFireballObjectInstance fireball = spawnChild(
                    () -> new SolFireballObjectInstance(spawn, this, angle));
            fireballs.add(fireball);
            fireballsRemaining++;
        }
    }

    private State resolveInitialState(int subtype) {
        int routine = (subtype & 0xFF) + 2;
        return routine == 4 ? State.AFTER_FIRE : State.WAIT_FOR_PLAYER;
    }

    void onFireballLaunched() {
        fireballsRemaining--;
        if (fireballsRemaining <= 0) {
            state = State.AFTER_FIRE;
        }
    }

    void attachFireballForRewind(SolFireballObjectInstance fireball) {
        if (fireball != null && !fireballs.contains(fireball)) {
            fireballs.add(fireball);
        }
    }

    int getOrbitDirection() {
        return orbitDirection;
    }

    int getCurrentMappingFrame() {
        return animFrame;
    }

    boolean isXFlip() {
        return xFlip;
    }

    @Override
    public void onUnload() {
        for (SolFireballObjectInstance fireball : fireballs) {
            fireball.detachFromParent();
        }
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

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.SOL);
        if (renderer == null) return;

        renderer.drawFrameIndex(animFrame, currentX, currentY, xFlip, false);
    }

    static SpriteAnimationSet getFireballAnimations() {
        return FIREBALL_ANIMATIONS;
    }

    private static SpriteAnimationSet createBodyAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        // Ani_obj95_a script 0: dc.b $F,0,$FF
        set.addScript(0, new SpriteAnimationScript(
                0x0F,
                List.of(0),
                SpriteAnimationEndAction.LOOP,
                0));
        // Ani_obj95_a script 1: dc.b $F,1,2,$FE,1
        set.addScript(1, new SpriteAnimationScript(
                0x0F,
                List.of(1, 2),
                SpriteAnimationEndAction.LOOP_BACK,
                1));
        return set;
    }

    private static SpriteAnimationSet createFireballAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        // Ani_obj95_b script 0: dc.b 5,3,4,$FF
        set.addScript(0, new SpriteAnimationScript(
                0x05,
                List.of(3, 4),
                SpriteAnimationEndAction.LOOP,
                0));
        return set;
    }
}
