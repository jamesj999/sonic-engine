package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameModule;
import com.openggf.game.PlayableEntity;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.ShieldType;
import com.openggf.level.objects.ShieldObjectInstance;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.Pattern;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.util.List;

/**
 * Lightning Shield visual object for S3K.
 * Uses DPLC-driven ROM art with animation scripts from Ani_LightningShield.
 */
public class LightningShieldObjectInstance extends ShieldObjectInstance {
    private static final int REAR_FRAME_THRESHOLD = 0x0E;

    private PlayerSpriteRenderer dplcRenderer;
    private SpriteAnimationSet animSet;
    private PlayerSpriteRenderer boundRenderer;
    private boolean artRefreshPending;
    private int currentAnimId;
    private int frameIndex;
    private int delayCounter;
    private int currentMappingFrame;

    public LightningShieldObjectInstance(AbstractPlayableSprite player) {
        super(player);
        currentAnimId = 0;
        frameIndex = 0;
        delayCounter = 0;
        currentMappingFrame = 0;
        initAnimation(0);
    }

    @Override
    public boolean matchesShieldType(ShieldType type) {
        return type == ShieldType.LIGHTNING;
    }

    @Override
    public void refreshArtAfterRewindRestore() {
        artRefreshPending = true;
        boundRenderer = null;
        if (dplcRenderer != null) {
            dplcRenderer.invalidateDplcCache();
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        super.update(vIntRunCount, player);
        if (isShieldDestroyed()) return;
        ensureShieldArtLoaded();
        stepAnimation();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isShieldDestroyed() || !isShieldVisible()) {
            return;
        }
        ensureShieldArtLoaded();
        if (dplcRenderer != null) {
            AbstractPlayableSprite player = ((AbstractPlayableSprite) getPlayer());
            if (player == null) return;
            int cx = player.getCentreX();
            int cy = player.getCentreY();
            dplcRenderer.drawFrame(currentMappingFrame, cx, cy, false, false);
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
        appendWireDiamond(commands, cx, cy, half, 0.0f, 0.9f, 1.0f);
    }

    /** Sets the current animation and resets playback state. */
    public void setAnimation(int animId) {
        if (animId != currentAnimId) {
            initAnimation(animId);
        }
    }

    @Override
    public void onAbilityActivated(int actionId) {
        triggerSparks(true);
    }

    /**
     * Obj_LightningShield_CreateSpark (docs/skdisasm/sonic3k.asm:34811-34858).
     * Creates 4 spark particles with diagonal velocities; shield stays on script 0.
     */
    public void triggerSparks() {
        triggerSparks(false);
    }

    private void triggerSparks(boolean afterDynamicObjectPass) {
        AbstractPlayableSprite player = ((AbstractPlayableSprite) getPlayer());
        if (player == null) return;
        Sonic3kObjectArtProvider artProvider = getS3kArtProvider();
        SpriteAnimationSet sparkAnimationSet = null;
        Pattern[] sparkTiles = null;
        if (artProvider != null) {
            SpriteArtSet sparkArtSet = artProvider.getShieldArtSet(Sonic3kObjectArtKeys.LIGHTNING_SPARK);
            if (sparkArtSet != null) {
                sparkAnimationSet = sparkArtSet.animationSet();
                sparkTiles = sparkArtSet.artTiles();
            }
        }

        int cx = player.getCentreX();
        int cy = player.getCentreY();
        // ROM allocates the children before assigning art state, so headless/object
        // parity must not depend on loaded renderer art (sonic3k.asm:34811-34844).
        int[][] velocities = {
            {-0x200, -0x200}, {0x200, -0x200},
            {-0x200,  0x200}, {0x200,  0x200}
        };
        for (int[] vel : velocities) {
            LightningSparkObjectInstance spark = new LightningSparkObjectInstance(
                    cx, cy, vel[0], vel[1], sparkAnimationSet, sparkTiles);
            if (afterDynamicObjectPass && tryServices() != null
                    && tryServices().objectManager() != null) {
                tryServices().objectManager().queueDynamicObjectAfterExec(spark);
            } else {
                spawnDynamicObject(spark);
            }
        }
    }

    @Override
    public int getPriorityBucket() {
        // ROM Obj_LightningShield_Display switches from priority $80 to $200 once the
        // mapping frame reaches $0E, putting the rear half of the animation behind Sonic.
        return RenderPriority.clamp(currentMappingFrame >= REAR_FRAME_THRESHOLD ? 4 : 1);
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

    private void ensureShieldArtLoaded() {
        if (artRefreshPending) {
            boundRenderer = null;
            if (dplcRenderer != null) {
                dplcRenderer.invalidateDplcCache();
            }
            artRefreshPending = false;
        }
        if (dplcRenderer != null && animSet != null) {
            return;
        }
        Sonic3kObjectArtProvider artProvider = getS3kArtProvider();
        if (artProvider == null) {
            return;
        }
        if (dplcRenderer == null) {
            dplcRenderer = artProvider.getShieldDplcRenderer(Sonic3kObjectArtKeys.LIGHTNING_SHIELD);
            if (dplcRenderer != null && dplcRenderer != boundRenderer) {
                dplcRenderer.invalidateDplcCache();
                boundRenderer = dplcRenderer;
            }
        }
        if (animSet == null) {
            SpriteArtSet artSet = artProvider.getShieldArtSet(Sonic3kObjectArtKeys.LIGHTNING_SHIELD);
            if (artSet != null && artSet.animationSet() != null) {
                animSet = artSet.animationSet();
                initAnimation(currentAnimId);
            }
        }
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
