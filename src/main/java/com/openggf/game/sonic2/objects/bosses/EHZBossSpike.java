package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * EHZ Boss - Retractable spike hazard.
 * ROM Reference: s2.asm:63415-63496 (loc_2F7F4 - Obj56_Spike)
 */
public class EHZBossSpike extends AbstractBossChild implements TouchResponseProvider, RewindRecreatable {
    private static final int CAMERA_GATE_X = 0x28F0;
    private static final int APPROACH_TARGET_X = 0x299A;
    private static final int OBJOFF_FLAGS = 0x2D;
    private static final int FLAG_ACTIVE = 0x02;
    private static final int FLAG_SEPARATE = 0x08;
    private static final int VEHICLE_FRAME_OFFSET = 7;

    private final ObjectAnimationState animationState;
    private int routineSecondary;
    private boolean separated;
    private int separationVelX;
    private int renderFlags;
    private boolean collisionEnabled;

    public EHZBossSpike(Sonic2EHZBossInstance parent) {
        // ROM: s2.asm:63194 Obj56_Init sets the spike's priority(a0) = 2 (same as the
        // front wheels), one in front of the ground vehicle's priority = 3. Lower priority
        // draws on top, so the drillcone renders in front of the car body. Mirror that with
        // engine bucket 3 (the front wheels' bucket); sharing the vehicle's bucket 4 left the
        // spike behind it on the within-bucket slot tiebreak.
        super(parent, "EHZ Boss Spike", 3, Sonic2ObjectIds.EHZ_BOSS);
        this.animationState = new ObjectAnimationState(
                EHZBossAnimations.getVehicleAnimations(),
                0,
                1);
        this.routineSecondary = 0;
        this.separated = false;
        this.separationVelX = 0;
        this.renderFlags = 0;
        this.collisionEnabled = false;
        this.currentX = 0x2AF0 - 0x36;
        this.currentY = parent.getInitialY() + 0x08;
    }

    @Override
    public EHZBossSpike recreateForRewind(RewindRecreateContext ctx) {
        Sonic2EHZBossInstance boss = EhzBossRewindLinks.requireNearestBoss(ctx, "EHZ boss spike");
        return new EHZBossSpike(boss);
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

        switch (routineSecondary) {
            case 0 -> updateApproach();
            case 2 -> updateLinked(player);
            default -> updateLinked(player);
        }

        updateDynamicSpawn();
    }

    private void updateApproach() {
        Camera camera = services().camera();
        if (camera != null && camera.getMinX() < CAMERA_GATE_X) {
            return;
        }

        if (currentX > APPROACH_TARGET_X) {
            currentX -= 1;
        } else {
            currentX = APPROACH_TARGET_X;
            routineSecondary = 2;
        }
    }

    private void updateLinked(AbstractPlayableSprite player) {
        Sonic2EHZBossInstance ehzParent = (Sonic2EHZBossInstance) parent;
        int flags = ehzParent.getCustomFlag(OBJOFF_FLAGS);

        // ROM: s2.asm:63836 - Check if spike separated (bit 3 of objoff_2D)
        if ((flags & FLAG_SEPARATE) != 0) {
            updateSeparated(ehzParent);
            return;
        }

        checkSeparation(player, ehzParent);

        // ROM: s2.asm:63844 - Only enable collision when boss is active (bit 1 of objoff_2D)
        // This means NO collision during approach (SUB0) or descent (SUB2)
        boolean active = (flags & FLAG_ACTIVE) != 0;
        boolean defeated = ehzParent.getState().defeated;

        if (!active || defeated) {
            collisionEnabled = false;
            return;
        }

        renderFlags = ehzParent.getState().renderFlags;
        currentX = parent.getX();
        currentY = parent.getY() + 0x10;
        int xOffset = -0x36;
        if ((renderFlags & 1) != 0) {
            xOffset = -xOffset;
        }
        currentX += xOffset;
        collisionEnabled = true;
        animationState.update();
    }

    private void updateSeparated(Sonic2EHZBossInstance ehzParent) {
        if (!separated) {
            separated = true;
            renderFlags = ehzParent.getState().renderFlags;
            separationVelX = (renderFlags & 1) != 0 ? 3 : -3;
        }
        currentX += separationVelX;

        // ROM: When separated, spike maintains its collision state
        // But we disable it if boss is defeated
        if (ehzParent.getState().defeated) {
            collisionEnabled = false;
        }
        // Otherwise collision stays as it was when separated (enabled)

        animationState.update();
    }

    private void checkSeparation(AbstractPlayableSprite player, Sonic2EHZBossInstance ehzParent) {
        if (player == null || ehzParent.getState().hitCount != 1) {
            return;
        }
        int dx = currentX - player.getCentreX();
        boolean flip = (ehzParent.getState().renderFlags & 1) != 0;
        if (dx < 0) {
            if (flip) {
                ehzParent.setCustomFlag(OBJOFF_FLAGS, ehzParent.getCustomFlag(OBJOFF_FLAGS) | FLAG_SEPARATE);
            }
            return;
        }
        if (!flip) {
            ehzParent.setCustomFlag(OBJOFF_FLAGS, ehzParent.getCustomFlag(OBJOFF_FLAGS) | FLAG_SEPARATE);
        }
    }

    @Override
    public int getCollisionFlags() {
        // ROM: s2.asm:63440-63442 - Check FLAG_ACTIVE before setting collision_flags
        // btst #1,objoff_2D(a1)
        // beq.w JmpTo35_DisplaySprite  ; boss not moving yet (inactive)
        // move.b #$8B,collision_flags(a0)

        if (parent == null) {
            return 0;
        }

        Sonic2EHZBossInstance ehzParent = (Sonic2EHZBossInstance) parent;

        // Check if boss is defeated
        if (ehzParent.getState().defeated) {
            return 0;
        }

        // Explicitly check boss state - spike only active during battle phase
        int bossState = ehzParent.getState().routineSecondary;
        if (bossState < 0x04) {  // SUB0 or SUB2 (approach/descent)
            return 0;
        }
        if (bossState >= 0x06) {  // SUB6 or later (defeated/fleeing)
            return 0;
        }

        // Check if boss is active (bit 1 of objoff_2D)
        int flags = ehzParent.getCustomFlag(OBJOFF_FLAGS);
        boolean active = (flags & FLAG_ACTIVE) != 0;

        if (!active) {
            return 0; // Boss not active yet - no collision
        }

        // Check if spike is separated (bit 3 of objoff_2D)
        boolean separated = (flags & FLAG_SEPARATE) != 0;

        // Spike has collision when boss is active (whether linked or separated)
        return 0x8B;
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
        if (renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS) == null || !renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS).isReady()) {
            return;
        }

        boolean flipped = (renderFlags & 1) != 0;
        int frameIndex = VEHICLE_FRAME_OFFSET + animationState.getMappingFrame();
        renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS).drawFrameIndex(frameIndex, currentX, currentY, flipped, false);
    }
}
