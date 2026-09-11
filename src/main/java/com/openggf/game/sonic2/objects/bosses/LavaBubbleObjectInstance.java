package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Lava Bubble - Spawned when HTZ boss lava ball hits the ground.
 * ROM Reference: s2.asm:64016-64060 (Obj20_LavaBubble transformation)
 * <p>
 * The lava bubble stays in place where the lava ball hit, animates with a
 * bubbling effect, and damages the player on contact. Despawns after ~120 frames.
 * <p>
 * Uses collision flags 0x98 (enemy projectile with size).
 */
public class LavaBubbleObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnCoordinateRewindRecreatable {

    // Animation constants
    private static final int ANIM_DELAY = 8;  // Frames between animation changes
    private static final int FRAME_COUNT = 2; // Total animation frames (uses Obj20 fireball frames)

    // Lifetime constant (ROM: approximately 2 seconds)
    private static final int LIFETIME = 120;

    // Collision constants (ROM: move.b #$98,collision_flags(a1))
    private static final int COLLISION_FLAGS = 0x98;

    private int x;
    private int y;
    private int animFrame;
    private int animTimer;
    private int lifetime;

    /**
     * Creates a lava bubble at the specified position.
     *
     * @param x X position where lava ball hit ground
     * @param y Y position (ground level)
     */
    public LavaBubbleObjectInstance(int x, int y) {
        super(new ObjectSpawn(x, y, Sonic2ObjectIds.LAVA_BUBBLE, 0, 0, false, 0), "Lava Bubble");
        this.x = x;
        this.y = y;
        this.animFrame = 0;
        this.animTimer = ANIM_DELAY;
        this.lifetime = LIFETIME;
    }

    LavaBubbleObjectInstance(ObjectSpawn spawn) {
        this(spawn.x(), spawn.y());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Update animation (bubbling effect)
        animTimer--;
        if (animTimer <= 0) {
            animTimer = ANIM_DELAY;
            animFrame = (animFrame + 1) % FRAME_COUNT;
        }

        // Check lifetime
        lifetime--;
        if (lifetime <= 0) {
            setDestroyed(true);
        }
    }

    /**
     * Returns collision flags for touch response.
     * ROM: move.b #$98,collision_flags(a1) - enemy projectile collision
     */
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
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        // ROM: Obj20 uses ArtNem_HtzFireball2 with Obj20_MapUnc_23254 mappings.
        // After boss transformation, switches to ArtNem_HtzFireball1 (frames 3-4).
        // Try the dedicated LAVA_BUBBLE sheet first, fall back to SOL frames.
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.LAVA_BUBBLE);
        int frame;
        if (renderer != null && renderer.isReady()) {
            frame = animFrame; // Frames 0-1 in the dedicated sheet
        } else {
            renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.SOL);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            frame = 3 + animFrame; // Fallback to SOL fireball frames
        }

        renderer.drawFrameIndex(frame, x, y, false, false);
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
        return 4;  // Same priority as boss and lava balls
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), 0, spawn.respawnTracked(), spawn.rawYWord());
    }
}
