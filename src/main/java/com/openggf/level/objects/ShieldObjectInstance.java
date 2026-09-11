package com.openggf.level.objects;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.graphics.RenderPriority;

import com.openggf.graphics.GLCommand;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PowerUpObject;
import com.openggf.game.ShieldType;
import com.openggf.game.rewind.RewindTransient;

import java.util.List;

public class ShieldObjectInstance extends AbstractObjectInstance
        implements PowerUpObject, PlayerBoundShieldRewindRecreatable {
    @RewindTransient(reason = "player binding is structural and restored by the power-up spawner")
    private final PlayableEntity player;
    private final PatternSpriteRenderer renderer;

    // S2 animation from disassembly (Ani_obj38): 5, 0, 5, 1, 5, 2, 5, 3, 5, 4
    // Alternates between expanded frame (5) and smaller frames (0-4)
    private static final int[] S2_ANIMATION_SEQUENCE = { 5, 0, 5, 1, 5, 2, 5, 3, 5, 4 };

    // S1 animation from disassembly (Ani_Shield .shield): 1, 0, 2, 0, 3, 0
    // Alternates between full frames (1,2,3) and the invisible frame (0)
    private static final int[] S1_ANIMATION_SEQUENCE = { 1, 0, 2, 0, 3, 0 };

    // S2 Ani_obj38 delay = 0 → advance every frame; S1 Ani_Shield delay = 1 → advance every 2 frames
    private static final int S2_ANIMATION_SPEED = 1;
    private static final int S1_ANIMATION_SPEED = 2;

    @RewindTransient(reason = "animation sequence is constructor-selected immutable game/art configuration")
    private final int[] animationSequence;
    @RewindTransient(reason = "animation speed is constructor-selected immutable game/art configuration")
    private final int animationSpeed;
    private int sequenceIndex = 0;
    private int animationFrameTimer;
    private boolean destroyed = false;
    private boolean visible = true;

    public ShieldObjectInstance(PlayableEntity player) {
        super(null, "Shield");
        this.player = player;
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager != null) {
            this.renderer = renderManager.getShieldRenderer();
            // Detect S1 vs S2 from sheet frame count: S1 has 4 frames, S2 has 6
            ObjectSpriteSheet sheet = renderManager.getSheet(ObjectArtKeys.SHIELD);
            if (sheet != null && sheet.getFrameCount() <= 4) {
                this.animationSequence = S1_ANIMATION_SEQUENCE;
                this.animationSpeed = S1_ANIMATION_SPEED;
            } else {
                this.animationSequence = S2_ANIMATION_SEQUENCE;
                this.animationSpeed = S2_ANIMATION_SPEED;
            }
        } else {
            this.renderer = null;
            this.animationSequence = S2_ANIMATION_SEQUENCE;
            this.animationSpeed = S2_ANIMATION_SPEED;
        }
        this.animationFrameTimer = this.animationSpeed;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public boolean isShieldFor(PlayableEntity target, ShieldType type) {
        return player == target && matchesShieldType(type);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (destroyed) {
            return;
        }
        // AnimateSprite uses this object's obTimeFrame countdown; it is not
        // synchronized to the global V-int counter. The script delay is one less
        // than the number of updates each mapping frame remains displayed.
        animationFrameTimer--;
        if (animationFrameTimer < 0) {
            animationFrameTimer = animationSpeed - 1;
            sequenceIndex = (sequenceIndex + 1) % animationSequence.length;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed || !visible || renderer == null) {
            return;
        }

        int currentFrame = getCurrentFrame();
        renderer.drawFrameIndex(currentFrame, player.getCentreX(), player.getCentreY(), false, false);
    }

    @Override
    public boolean isHighPriority() {
        return player != null && player.isHighPriority();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1);
    }

    public void destroy() {
        ObjectLifetimeOps.destroyLatched(this);
    }

    protected PlayableEntity getPlayer() {
        return player;
    }

    protected boolean hasRenderer() {
        return renderer != null;
    }

    protected boolean isShieldDestroyed() {
        return destroyed;
    }

    protected boolean isShieldVisible() {
        return visible;
    }

    protected int getSequenceIndex() {
        return sequenceIndex;
    }

    protected int getCurrentFrame() {
        return animationSequence[sequenceIndex];
    }

}
