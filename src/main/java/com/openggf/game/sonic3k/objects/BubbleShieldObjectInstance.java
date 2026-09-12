package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameModule;
import com.openggf.game.PlayableEntity;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.ShieldType;
import com.openggf.level.objects.ShieldObjectInstance;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.graphics.GLCommand;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.util.List;

/**
 * Bubble Shield visual object for S3K.
 * Uses DPLC-driven ROM art with animation scripts from Ani_BubbleShield.
 */
public class BubbleShieldObjectInstance extends ShieldObjectInstance {

    private final ShieldAnimationArtLifecycle animationLifecycle = new ShieldAnimationArtLifecycle(0);

    public BubbleShieldObjectInstance(AbstractPlayableSprite player) {
        super(player);
    }

    @Override
    public boolean matchesShieldType(ShieldType type) {
        return type == ShieldType.BUBBLE;
    }

    @Override
    public void refreshArtAfterRewindRestore() {
        animationLifecycle.refreshArtAfterRewindRestore();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        super.update(vIntRunCount, player);
        if (isShieldDestroyed()) return;
        animationLifecycle.ensureArtLoaded(this::loadShieldArt);
        animationLifecycle.stepAnimation();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isShieldDestroyed() || !isShieldVisible()) {
            return;
        }
        animationLifecycle.ensureArtLoaded(this::loadShieldArt);
        PlayerSpriteRenderer dplcRenderer = animationLifecycle.renderer();
        if (dplcRenderer != null) {
            AbstractPlayableSprite player = ((AbstractPlayableSprite) getPlayer());
            if (player == null) return;
            int cx = player.getCentreX();
            int cy = player.getCentreY();
            dplcRenderer.drawFrame(animationLifecycle.mappingFrame(), cx, cy, false, false);
            return;
        }
        if (hasRenderer()) {
            super.appendRenderCommands(commands);
            return;
        }
        // Wireframe fallback
        AbstractPlayableSprite player = ((AbstractPlayableSprite) getPlayer());
        if (player == null) return;
        int cx = player.getCentreX();
        int cy = player.getCentreY();
        boolean expanded = (getSequenceIndex() % 2) == 0;
        int half = expanded ? 18 : 14;
        appendWireDiamond(commands, cx, cy, half, 0.2f, 0.4f, 1.0f);
    }

    /** Sets the current animation and resets playback state. */
    public void setAnimation(int animId) {
        if (animId != animationLifecycle.animationId()) {
            animationLifecycle.setAnimation(animId);
        }
    }

    @Override
    public void onAbilityActivated(int actionId) {
        setAnimation(actionId);
    }

    private ShieldAnimationArtLifecycle.Art loadShieldArt() {
        Sonic3kObjectArtProvider artProvider = getS3kArtProvider();
        if (artProvider == null) {
            return null;
        }
        SpriteArtSet artSet = artProvider.getShieldArtSet(Sonic3kObjectArtKeys.BUBBLE_SHIELD);
        return new ShieldAnimationArtLifecycle.Art(
                artProvider.getShieldDplcRenderer(Sonic3kObjectArtKeys.BUBBLE_SHIELD),
                artSet != null ? artSet.animationSet() : null);
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
