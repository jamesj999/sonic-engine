package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractMonitorObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.SpawnNullableReferenceRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

import java.util.List;

/**
 * Sonic 2 monitor contents object (ROM Obj2E).
 * <p>
 * The broken monitor shell remains Obj26. This dynamic object owns the rising
 * icon, power-up timing, and final self-delete just like the ROM's separate SST
 * allocation from Obj26_Break.
 */
public final class MonitorContentsObjectInstance extends AbstractMonitorObjectInstance
        implements SpawnNullableReferenceRewindRecreatable {
    private static final int ICON_FRAME_OFFSET = 1;

    private int subtype;

    public MonitorContentsObjectInstance(int x, int y, int subtype, PlayableEntity player) {
        this(new ObjectSpawn(x, y, Sonic2ObjectIds.MONITOR_CONTENTS, subtype, 0, false, 0), player);
    }

    public MonitorContentsObjectInstance(ObjectSpawn spawn, PlayableEntity player) {
        super(spawn, "MonitorContents");
        this.subtype = spawn.subtype() & 0x0F;
        startIconRise(spawn.y(), player);
    }

    private MonitorContentsObjectInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    void delayFirstIconUpdateForPassedSlot() {
        iconPendingInit = true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        updateDynamicSpawn(spawn.x(), iconSubY >> 8);
        updateIcon();
    }

    @Override
    protected void applyPowerup(PlayableEntity player) {
        MonitorObjectInstance.applyMonitorPowerup(subtype, player, services());
    }

    @Override
    protected void onIconDeactivated() {
        ObjectLifetimeOps.expireDynamic(this);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || !iconActive) {
            return;
        }
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getMonitorRenderer();
        ObjectSpriteSheet sheet = renderManager.getMonitorSheet();
        int frameIndex = subtype + ICON_FRAME_OFFSET;
        if (renderer == null || !renderer.isReady() || sheet == null
                || frameIndex < 0 || frameIndex >= sheet.getFrameCount()) {
            return;
        }
        SpriteMappingFrame frame = sheet.getFrame(frameIndex);
        if (frame == null || frame.pieces().isEmpty()) {
            return;
        }
        SpriteMappingPiece iconPiece = frame.pieces().get(0);
        renderer.drawPieces(List.of(iconPiece), spawn.x(), iconSubY >> 8, false, false);
    }
}
