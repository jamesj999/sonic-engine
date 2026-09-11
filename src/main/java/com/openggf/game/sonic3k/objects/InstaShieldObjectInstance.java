package com.openggf.game.sonic3k.objects;

import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.InstaShieldHandle;
import com.openggf.game.PlayableEntity;
import com.openggf.game.GameModule;
import com.openggf.game.ObjectArtProvider;
import com.openggf.level.objects.ShieldObjectInstance;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.graphics.GLCommand;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.util.List;

/**
 * Insta-Shield visual object for S3K.
 * Uses DPLC-driven ROM art with animation scripts from Ani_InstaShield.
 * Idles on animation 0 (invisible) and plays the attack animation (1) on ability use.
 * Transitions doubleJumpFlag from 1 to 2 at mapping frame 7 (end of attack animation).
 */
public class InstaShieldObjectInstance extends ShieldObjectInstance implements InstaShieldHandle {

    private static final int IDLE_ANIM = 0;
    private static final int ATTACK_ANIM = 1;
    private static final int FINAL_FRAME = 7;

    private PlayerSpriteRenderer dplcRenderer;
    private SpriteAnimationSet animSet;
    private int currentAnimId;
    private int frameIndex;
    private int delayCounter;
    private int currentMappingFrame;

    public InstaShieldObjectInstance(AbstractPlayableSprite player) {
        super(player);
        acquireArt();
        currentAnimId = IDLE_ANIM;
        frameIndex = 0;
        delayCounter = 0;
        currentMappingFrame = 0;
        initAnimation(IDLE_ANIM);
    }

    /**
     * Acquires DPLC renderer and animation set from the art provider.
     * Called at construction and lazily on first triggerAttack() if art wasn't
     * available at construction time (sprite created before level load).
     */
    private void acquireArt() {
        Sonic3kObjectArtProvider artProvider = getS3kArtProvider();
        if (artProvider != null) {
            PlayerSpriteRenderer r = artProvider.getShieldDplcRenderer(Sonic3kObjectArtKeys.INSTA_SHIELD);
            if (r != null) {
                this.dplcRenderer = r;
                SpriteArtSet artSet = artProvider.getShieldArtSet(Sonic3kObjectArtKeys.INSTA_SHIELD);
                this.animSet = artSet != null ? artSet.animationSet() : null;
                return;
            }
        }
        // Cross-game donation is exposed through ObjectServices directly, not the host module's
        // gameService() registry. S2/S1 modules won't return CrossGameFeatureProvider here.
        CrossGameFeatureProvider donor = services().crossGameFeatures();
        if (donor != null) {
            this.dplcRenderer = donor.getInstaShieldRenderer();
            SpriteArtSet artSet = donor.getInstaShieldArtSet();
            this.animSet = artSet != null ? artSet.animationSet() : null;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isShieldDestroyed()) return;
        stepAnimation();
        if (currentMappingFrame == FINAL_FRAME && player.getDoubleJumpFlag() == 1) {
            player.setDoubleJumpFlag(2);
        }
    }

    /** Invalidates the DPLC tile cache, forcing re-upload on the next draw.
     *  Must be called after seamless level transitions that reload the pattern buffer. */
    public void invalidateDplcCache() {
        if (dplcRenderer != null) {
            dplcRenderer.invalidateDplcCache();
        }
    }

    /** Triggers the insta-shield attack animation. */
    public void triggerAttack() {
        // Lazy init: art may not have been loaded at construction time
        // (sprite created before level load). Acquire it now if needed.
        if (animSet == null) {
            acquireArt();
        }
        initAnimation(ATTACK_ANIM);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isShieldDestroyed() || !isShieldVisible()) {
            return;
        }
        AbstractPlayableSprite player = ((AbstractPlayableSprite) getPlayer());
        if (player == null) return;
        if (player.getInvincibleFrames() > 0) {
            return;
        }
        if (player.getShieldType() != null) {
            return;
        }
        if (currentAnimId == IDLE_ANIM) {
            return;
        }
        if (dplcRenderer != null) {
            int cx = player.getCentreX();
            int cy = player.getCentreY();
            boolean hFlip = player.getDirection() == Direction.LEFT;
            dplcRenderer.drawFrame(currentMappingFrame, cx, cy, hFlip, false);
            return;
        }
        if (hasRenderer()) {
            super.appendRenderCommands(commands);
            return;
        }
        // Wireframe fallback
        int cx = player.getCentreX();
        int cy = player.getCentreY();
        appendWireDiamond(commands, cx, cy, 18, 1.0f, 1.0f, 1.0f);
    }

    private void initAnimation(int animId) {
        currentAnimId = animId;
        frameIndex = 0;
        if (animSet != null) {
            SpriteAnimationScript script = animSet.getScript(animId);
            if (script != null) {
                delayCounter = script.delay();
                if (!script.frames().isEmpty()) {
                    currentMappingFrame = script.frames().get(0);
                }
            }
        }
    }

    private void stepAnimation() {
        if (animSet == null) return;
        SpriteAnimationScript script = animSet.getScript(currentAnimId);
        if (script == null || script.frames().isEmpty()) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }
        delayCounter = script.delay();

        frameIndex++;
        if (frameIndex >= script.frames().size()) {
            switch (script.endAction()) {
                case LOOP -> frameIndex = 0;
                case LOOP_BACK -> frameIndex = Math.max(0, script.frames().size() - script.endParam());
                case SWITCH -> { initAnimation(script.endParam()); return; }
                case HOLD -> frameIndex = script.frames().size() - 1;
            }
        }
        currentMappingFrame = script.frames().get(frameIndex);
    }

    private void appendWireDiamond(List<GLCommand> commands,
            int cx, int cy, int half, float r, float g, float b) {
        int top = cy - half;
        int bottom = cy + half;
        int left = cx - half;
        int right = cx + half;
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, cx, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, cy, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, right, cy, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, cx, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, cx, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, cy, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, left, cy, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID, r, g, b, cx, top, 0, 0));
    }

    private Sonic3kObjectArtProvider getS3kArtProvider() {
        GameModule module = services().gameModule();
        if (module == null) return null;
        ObjectArtProvider provider = module.getObjectArtProvider();
        return (provider instanceof Sonic3kObjectArtProvider s3k) ? s3k : null;
    }
}
