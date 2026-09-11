package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * EHZ Boss - Spinning propeller blades.
 * ROM Reference: s2.asm:63176-63231 (loc_2F54E - Obj56_Propeller normal)
 * ROM Reference: s2.asm:63166-63173 (loc_2F52A - Obj56_PropellerReloaded after defeat)
 */
public class EHZBossPropeller extends AbstractBossChild implements RewindRecreatable {
    private static final int HELICOPTER_SOUND_INTERVAL = 32;
    private static final int OBJOFF_FLAGS = 0x2D;
    private static final int FLAG_GROUNDED = 0x01;
    private static final int FLAG_FLYING_OFF = 0x04;
    private static final int FLAG_FINISHED = 0x10;

    private final ObjectAnimationState animationState;
    private int routineSecondary;
    private int timer;
    private boolean reloading;
    private int renderFlags;

    public EHZBossPropeller(Sonic2EHZBossInstance parent) {
        super(parent, "EHZ Boss Propeller", 3, Sonic2ObjectIds.EHZ_BOSS);
        this.animationState = new ObjectAnimationState(
                EHZBossAnimations.getPropellerAnimations(),
                0,
                0);
        this.routineSecondary = 0;
        this.timer = 0;
        this.reloading = false;
        this.renderFlags = 0;
    }

    @Override
    public EHZBossPropeller recreateForRewind(RewindRecreateContext ctx) {
        Sonic2EHZBossInstance boss = EhzBossRewindLinks.requireNearestBoss(ctx, "EHZ boss propeller");
        return new EHZBossPropeller(boss);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed() || !shouldUpdate(vIntRunCount)) {
            return;
        }

        if (parent == null) {
            setDestroyed(true);
            return;
        }

        // Destroy when boss has finished flying off-screen
        Sonic2EHZBossInstance ehzParent = (Sonic2EHZBossInstance) parent;
        int parentFlags = ehzParent.getCustomFlag(OBJOFF_FLAGS);
        if ((parentFlags & FLAG_FINISHED) != 0) {
            setDestroyed(true);
            return;
        }

        if (reloading) {
            updateReloading(vIntRunCount, parentFlags);
            updateDynamicSpawn();
            return;
        }

        switch (routineSecondary) {
            case 0 -> updateAirborne(vIntRunCount, parentFlags);
            case 2 -> updateLanding();
            default -> updateAirborne(vIntRunCount, parentFlags);
        }

        updateDynamicSpawn();
    }

    private void updateAirborne(int vIntRunCount, int parentFlags) {
        Sonic2EHZBossInstance ehzParent = (Sonic2EHZBossInstance) parent;
        boolean grounded = (parentFlags & FLAG_GROUNDED) != 0;

        if (grounded) {
            animationState.setAnimId(1);
            timer = 0x18;
            routineSecondary = 2;
        } else {
            // Only play helicopter SFX when not flying off
            if ((parentFlags & FLAG_FLYING_OFF) == 0 && (vIntRunCount & (HELICOPTER_SOUND_INTERVAL - 1)) == 0) {
                services().playSfx(Sonic2Sfx.WING_FORTRESS.id);
            }
        }

        syncPositionWithParent();
        renderFlags = ehzParent.getState().renderFlags;
        animationState.update();
    }

    private void updateLanding() {
        timer--;
        if (timer < 0) {
            if (timer <= -0x10) {
                setDestroyed(true);
                return;
            }
            priority = 4;
            currentY += 1;
            return;
        }
        animationState.update();
    }

    private void updateReloading(int vIntRunCount, int parentFlags) {
        currentY -= 1;
        timer--;
        if (timer < 0) {
            reloading = false;
            routineSecondary = 0;
        }

        // Play helicopter SFX during reload if not flying off
        if ((parentFlags & FLAG_FLYING_OFF) == 0 && (vIntRunCount & (HELICOPTER_SOUND_INTERVAL - 1)) == 0) {
            services().playSfx(Sonic2Sfx.WING_FORTRESS.id);
        }

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

        boolean flipped = (renderFlags & 1) != 0;
        int frameIndex = animationState.getMappingFrame();
        renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS).drawFrameIndex(frameIndex, currentX, currentY, flipped, false);
    }

    /**
     * Reload propeller for flying-off sequence.
     * Called by parent when transitioning to SUBA_FLYING_OFF.
     */
    public void reload() {
        reloading = true;
        routineSecondary = 0;
        timer = 0x10;
        priority = 3;
        animationState.setAnimId(2);
        if (parent != null) {
            currentX = parent.getX();
            currentY = parent.getY() + 0x0C;
            renderFlags = ((Sonic2EHZBossInstance) parent).getState().renderFlags;
        }
    }
}
