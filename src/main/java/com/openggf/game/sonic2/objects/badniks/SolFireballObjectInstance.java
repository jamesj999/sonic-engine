package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sol fireball (Obj95 child) - orbiting then fired projectile.
 * Uses Ani_obj95_b and follows Obj95_FireballUpdate behavior.
 */
public class SolFireballObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private enum State {
        ORBIT,
        FLYING
    }

    private static final int COLLISION_FLAGS = 0x98; // HURT + size index 0x18
    private static final int X_VELOCITY = 0x200;     // 2 pixels/frame (8.8 fixed)

    private final ObjectAnimationState animationState;
    private SolBadnikInstance parent;
    private State state;
    private int angle;
    private int currentX;
    private int currentY;
    private int xVelocity;
    private final SubpixelMotion.State motionState;

    public SolFireballObjectInstance(ObjectSpawn spawn, SolBadnikInstance parent, int angle) {
        super(spawn, "SolFireball");
        this.parent = parent;
        this.angle = angle & 0xFF;
        this.state = State.ORBIT;
        this.currentX = parent != null ? parent.getX() : spawn.x();
        this.currentY = parent != null ? parent.getY() : spawn.y();
        this.motionState = new SubpixelMotion.State(currentX, currentY, 0, 0, 0, 0);
        this.animationState = new ObjectAnimationState(SolBadnikInstance.getFireballAnimations(), 0, 3);
    }

    private SolFireballObjectInstance() {
        this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), null, 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        SolBadnikInstance parent = Sonic2BadnikChildRewindLinks.nearestSol(ctx);
        SolFireballObjectInstance fireball = new SolFireballObjectInstance(ctx.spawn(), parent, 0);
        if (parent != null) {
            parent.attachFireballForRewind(fireball);
        }
        return fireball;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (state) {
            case ORBIT -> updateOrbit();
            case FLYING -> updateFlying();
        }
    }

    private void updateOrbit() {
        animationState.update();

        if (parent == null || parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (parent.getCurrentMappingFrame() == 2 && (angle & 0xFF) == 0x40) {
            launch();
            return;
        }

        int cos = TrigLookupTable.cosHex(angle);
        int sin = TrigLookupTable.sinHex(angle);
        currentX = parent.getX() + (cos >> 4);
        currentY = parent.getY() + (sin >> 4);

        angle = (angle + parent.getOrbitDirection()) & 0xFF;
    }

    private void updateFlying() {
        motionState.x = currentX;
        motionState.xVel = xVelocity;
        SubpixelMotion.moveX(motionState);
        currentX = motionState.x;

        if (!isOnScreen()) {
            setDestroyed(true);
            return;
        }

        animationState.update();
    }

    private void launch() {
        state = State.FLYING;
        xVelocity = -X_VELOCITY;
        if (parent != null && parent.isXFlip()) {
            xVelocity = -xVelocity;
        }
        if (parent != null) {
            parent.onFireballLaunched();
        }
    }

    void detachFromParent() {
        parent = null;
    }

    @Override
    public int getCollisionFlags() {
        if (isDestroyed()) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.standardEnemy();
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
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.SOL);
        if (renderer == null) return;

        int frame = animationState.getMappingFrame();
        renderer.drawFrameIndex(frame, currentX, currentY, false, false);
    }
}
