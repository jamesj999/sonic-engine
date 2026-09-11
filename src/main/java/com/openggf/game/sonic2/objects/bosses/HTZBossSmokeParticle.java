package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * HTZ Boss Smoke Particle - Smoke effect during defeat sequence.
 * ROM Reference: s2.asm Obj52_CreateSmoke (s2.asm:64116-64135)
 * Uses Obj52_MapUnc_30258 for mappings (4 frames of smoke animation).
 * <p>
 * The smoke drifts leftward and upward while animating through 4 frames.
 * ROM: move.w #-$60,x_vel(a1), move.w #-$C0,y_vel(a1)
 * Animation uses delay $11 (17 decimal) per frame.
 * Self-destructs after animation completes.
 */
public class HTZBossSmokeParticle extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // Animation constants (ROM: s2.asm:64132, 64139-64142)
    // ROM: move.b #$11,anim_frame_duration(a1) = 17 frames per animation frame
    private static final int ANIM_DELAY = 17;
    private static final int FRAME_COUNT = 4; // Total animation frames (frames 0-3)

    // Movement constants (ROM: s2.asm:64129-64130)
    // ROM: move.w #-$60,x_vel(a1), move.w #-$C0,y_vel(a1)
    private static final int X_VELOCITY = -0x60;
    private static final int Y_VELOCITY = -0xC0;

    private int x;
    private int y;
    private int xFixed;
    private int yFixed;
    private int animFrame;
    private int animTimer;

    /**
     * Creates a new smoke particle at the specified position.
     *
     * @param spawn        Object spawn data
     */
    public HTZBossSmokeParticle(ObjectSpawn spawn) {
        super(spawn, "HTZ Boss Smoke");
        this.x = spawn.x();
        this.y = spawn.y();
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        this.animFrame = 0;
        this.animTimer = ANIM_DELAY;
    }

    /**
     * Creates a smoke particle at specific coordinates.
     *
     * @param x            X position
     * @param y            Y position
     */
    public HTZBossSmokeParticle(int x, int y) {
        this(new ObjectSpawn(x, y, 0x52, 0x08, 0, false, 0));
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Apply velocity using 8.8 fixed-point math
        // ROM: s2.asm:64147-64156 - asl.l #8,d0 (scale by 256)
        // ROM: move.w #-$60,x_vel(a1), move.w #-$C0,y_vel(a1)
        xFixed += (X_VELOCITY << 8);
        yFixed += (Y_VELOCITY << 8);
        x = xFixed >> 16;
        y = yFixed >> 16;

        // Update animation
        // ROM: s2.asm:64139-64142 - subq.b #1,anim_frame_duration(a0)
        animTimer--;
        if (animTimer <= 0) {
            animTimer = ANIM_DELAY;
            animFrame++;
            if (animFrame >= FRAME_COUNT) {
                // Animation complete - destroy
                setDestroyed(true);
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || animFrame >= FRAME_COUNT) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.HTZ_BOSS_SMOKE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(animFrame, x, y, false, false);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return 1;  // ROM: move.b #1,priority(a1)
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), 0, spawn.respawnTracked(), spawn.rawYWord());
    }
}
