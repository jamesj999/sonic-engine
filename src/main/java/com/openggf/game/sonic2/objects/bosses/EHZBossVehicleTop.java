package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * EHZ Boss - Flying vehicle top part (Eggman capsule top).
 * ROM Reference: Obj56 routine 0x0E
 */
public class EHZBossVehicleTop extends AbstractBossChild implements RewindRecreatable {
    private static final int TOP_FRAME_OFFSET = 15;

    private final ObjectAnimationState animationState;
    private int renderFlags;
    private boolean flyingOff;

    public EHZBossVehicleTop(Sonic2EHZBossInstance parent) {
        super(parent, "EHZ Boss Vehicle Top", 4, Sonic2ObjectIds.EHZ_BOSS);
        this.animationState = new ObjectAnimationState(
                EHZBossAnimations.getTopAnimations(),
                1,
                1);
        this.renderFlags = 0;
        this.flyingOff = false;
    }

    @Override
    public EHZBossVehicleTop recreateForRewind(RewindRecreateContext ctx) {
        Sonic2EHZBossInstance boss = EhzBossRewindLinks.requireNearestBoss(ctx, "EHZ boss vehicle top");
        return new EHZBossVehicleTop(boss);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed() || !shouldUpdate(vIntRunCount)) {
            return;
        }
        if (parent == null || parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        syncPositionWithParent();
        renderFlags = ((Sonic2EHZBossInstance) parent).getState().renderFlags;

        // Destroy self when off-screen during flying off
        if (flyingOff && !isOnScreen(128)) {
            setDestroyed(true);
            return;
        }

        if (flyingOff) {
            animationState.setAnimId(4);
        } else {
            Sonic2EHZBossInstance ehzParent = (Sonic2EHZBossInstance) parent;
            if (ehzParent.getState().invulnerabilityTimer == 0x1F) {
                animationState.setAnimId(2);
            }

            boolean ball = player != null && (player.getRolling() || player.getAir() || player.getPinballMode());
            if (ball && animationState.getAnimId() != 2) {
                animationState.setAnimId(3);
            }
        }

        animationState.update();
        updateDynamicSpawn();
    }

    public void setFlyingOff() {
        flyingOff = true;
        animationState.setAnimId(4);
        animationState.update();
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
        if (renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS) == null || !renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS).isReady()) {
            return;
        }

        int frameIndex = TOP_FRAME_OFFSET + animationState.getMappingFrame();
        boolean flipped = (renderFlags & 1) != 0;
        renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS).drawFrameIndex(frameIndex, currentX, currentY, flipped, false, 0);
    }
}
