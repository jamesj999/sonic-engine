package com.openggf.game.sonic2.objects;
import com.openggf.level.objects.ObjectAnimationState;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * OOZ Burner Flame (Obj33 child, routine 4) - flame beneath the popping platform.
 * <p>
 * The flame is only visible and harmful when the platform has risen at least 20 pixels ($14)
 * above the flame's position. When visible, it animates with a flickering pattern and
 * hurts the player on contact.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 49431-49450 (Obj33_Flame)
 * <p>
 * Collision flags: $9B = HURT + size index $1B (8x4 pixels)
 */
public class OOZBurnerFlameObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // Collision flags: $9B = hurt (upper 2 bits = $80) + size index $1B
    private static final int COLLISION_FLAGS_ACTIVE = 0x9B;
    private static final int COLLISION_FLAGS_INACTIVE = 0;

    // Flame becomes visible when platform is >= $14 pixels above flame Y
    private static final int VISIBILITY_THRESHOLD = 0x14;

    // Animation: Ani_obj33 = dc.b 2, 2, 0, 2, 0, 2, 0, 1, $FF
    // delay=2, frames=[2, 0, 2, 0, 2, 0, 1], loop
    private static final SpriteAnimationSet FLAME_ANIMATIONS;

    static {
        FLAME_ANIMATIONS = new SpriteAnimationSet();
        FLAME_ANIMATIONS.addScript(0, new SpriteAnimationScript(
                2, // delay (display each frame for 3 VBlanks)
                List.of(2, 0, 2, 0, 2, 0, 1),
                SpriteAnimationEndAction.LOOP,
                0
        ));
    }
    private final OOZPoppingPlatformObjectInstance parent;
    private final ObjectAnimationState animationState;
    private boolean flameActive;

    public OOZBurnerFlameObjectInstance(ObjectSpawn spawn, OOZPoppingPlatformObjectInstance parent) {
        super(spawn, "OOZBurnerFlame");
        this.parent = parent;
        this.animationState = new ObjectAnimationState(FLAME_ANIMATIONS, 0, 0);
        this.flameActive = false;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx.spawn() == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        for (ObjectInstance inst : ctx.objectServices().objectManager().getActiveObjects()) {
            if (inst instanceof OOZPoppingPlatformObjectInstance platform
                    && !platform.isDestroyed()
                    && platform.getX() == ctx.spawn().x()
                    && platform.getHomeY() == ctx.spawn().y() + 0x10) {
                return new OOZBurnerFlameObjectInstance(ctx.spawn(), platform);
            }
        }
        return null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (parent == null || parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        // ROM: d0 = y_pos(flame) - y_pos(parent)
        // If distance < $14: flame off
        // If distance >= $14: flame on, animate
        int distance = spawn.y() - parent.getPlatformY();
        if (distance < VISIBILITY_THRESHOLD) {
            // Flame off (Obj33_FlameOff)
            // ROM: move.b #0,anim_frame(a0) - reset animation frame to 0
            flameActive = false;
            animationState.resetFrameIndex();
            return;
        }

        // Flame on - animate and enable collision
        flameActive = true;
        animationState.update();
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4); // Behind platform (priority 3)
    }

    @Override
    public int getCollisionFlags() {
        return flameActive ? COLLISION_FLAGS_ACTIVE : COLLISION_FLAGS_INACTIVE;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!flameActive) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.OOZ_BURN_FLAME);
        if (renderer == null) return;
        int frame = animationState.getMappingFrame();
        renderer.drawFrameIndex(frame, spawn.x(), spawn.y(), false, false);
    }

}
