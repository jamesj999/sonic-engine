package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x45 - CNZ Light Bulb ({@code Obj_CNZLightBulb}).
 *
 * <p>The ROM uses frame 0 normally, then latches frame 1 once water exists and
 * {@code Water_level} is above the bulb's {@code y_pos}.
 */
public final class CnzLightBulbInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int FRAME_NORMAL = 0;
    private static final int PRIORITY_BUCKET = 5; // Obj_CNZLightBulb: priority $280.
    private static final int FRAME_SUBMERGED = 1;

    private int renderFrame = FRAME_NORMAL;
    private boolean submerged;

    public CnzLightBulbInstance(ObjectSpawn spawn) {
        super(spawn, "CNZLightBulb");
    }


    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public CnzLightBulbInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzLightBulbInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        WaterSystem waterSystem = services().waterSystem();
        if (waterSystem == null) {
            return;
        }
        int zoneId = services().featureZoneId();
        int actId = services().featureActId();
        updateWaterState(waterSystem.hasWater(zoneId, actId), waterSystem.getWaterLevelY(zoneId, actId));
    }

    void updateWaterState(boolean hasWater, int waterLevel) {
        if (submerged || !hasWater) {
            return;
        }
        if (waterLevel < spawn.y()) {
            submerged = true;
            renderFrame = FRAME_SUBMERGED;
        }
    }

    int getRenderFrame() {
        return renderFrame;
    }

    boolean isSubmerged() {
        return submerged;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_LIGHT_BULB);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x02) != 0;
        renderer.drawFrameIndex(renderFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }
}
