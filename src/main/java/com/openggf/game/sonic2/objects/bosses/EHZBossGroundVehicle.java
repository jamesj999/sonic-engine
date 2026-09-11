package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * EHZ Boss - Ground vehicle body.
 * ROM Reference: Obj56 routine 0x06
 */
public class EHZBossGroundVehicle extends AbstractBossChild implements RewindRecreatable {
    private static final int APPROACH_TARGET_X = 0x29D0;
    private static final int APPROACH_START_X = 0x2AF0;
    private static final int CAMERA_GATE_X = 0x28F0;
    private static final int OBJOFF_FLAGS = 0x2D;
    private static final int FLAG_ACTIVE = 0x02;
    private static final int FLAG_FLYING_OFF = 0x04;
    private static final int VEHICLE_FRAME_OFFSET = 7;

    private int initialY;
    private int routineSecondary;
    private int renderFlags;

    public EHZBossGroundVehicle(Sonic2EHZBossInstance parent) {
        super(parent, "EHZ Boss Ground Vehicle", 4, Sonic2ObjectIds.EHZ_BOSS);  // Behind Sonic (2) and near-side wheel (3)
        this.routineSecondary = 0;
        this.initialY = parent.getInitialY();
        this.currentX = APPROACH_START_X;
        this.currentY = initialY;
    }

    @Override
    public EHZBossGroundVehicle recreateForRewind(RewindRecreateContext ctx) {
        Sonic2EHZBossInstance boss = EhzBossRewindLinks.requireNearestBoss(ctx, "EHZ boss ground vehicle");
        return new EHZBossGroundVehicle(boss);
    }

    @Override
    protected boolean destroyWhenParentDestroyed() {
        // ROM: Obj56_GroundVehicle (loc_2F5F6) never calls DeleteObject - the wrecked
        // car stays on the ground as debris after the main body is deleted on fly-off.
        // Opt out of the default parent-destroy cascade so it survives the boss's removal.
        return false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed() || !shouldUpdate(vIntRunCount)) {
            return;
        }

        // Don't destroy when parent is destroyed - persist on ground
        if (parent == null) {
            setDestroyed(true);
            return;
        }
        // If parent exists, continue update even if parent.isDestroyed()

        if (routineSecondary == 0) {
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
            currentY = initialY;
        } else {
            Sonic2EHZBossInstance ehzParent = (Sonic2EHZBossInstance) parent;
            int flags = ehzParent.getCustomFlag(OBJOFF_FLAGS);
            boolean active = (flags & FLAG_ACTIVE) != 0;
            boolean flyingOff = (flags & FLAG_FLYING_OFF) != 0;

            // During defeat falling (SUB6/SUB8), continue syncing so the body falls with Robotnik
            boolean defeatFalling = ehzParent.getState().routineSecondary == 0x06
                                 || ehzParent.getState().routineSecondary == 0x08;

            if (flyingOff) {
                // Once flying off, stop syncing (body stays on ground)
                return;
            }

            if (!active && !defeatFalling) {
                // Not active and not in defeat falling phase
                return;
            }

            syncPositionWithParent();
            currentY += 8;
            renderFlags = ehzParent.getState().renderFlags;
        }

        updateDynamicSpawn();
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
        renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS)
                .drawFrameIndex(VEHICLE_FRAME_OFFSET, currentX, currentY, flipped, false, 0);
    }
}
