package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractMonitorObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.SpawnCoordinateSubtypeDefaultArgsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

import java.util.List;

/**
 * Sonic 1 monitor contents object (ROM object $2E).
 * <p>
 * This is a real child allocation in the SST, separate from the broken monitor shell.
 * The first same-frame update consumes Pow_Main, then later updates perform the icon rise
 * and apply the monitor effect at the apex.
 */
public final class Sonic1MonitorPowerUpObjectInstance extends AbstractMonitorObjectInstance
        implements SpawnCoordinateSubtypeDefaultArgsRewindRecreatable {
    private static final int ICON_FRAME_OFFSET = 2;

    private int subtype;

    public Sonic1MonitorPowerUpObjectInstance(int x, int y, int subtype, PlayableEntity player) {
        super(new ObjectSpawn(x, y, Sonic1ObjectIds.POWER_UP, subtype, 0, false, 0), "PowerUp");
        this.subtype = subtype & 0xFF;
        startIconRise(y, player);
    }

    private Sonic1MonitorPowerUpObjectInstance() {
        this(0, 0, 0, null);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        updateDynamicSpawn(spawn.x(), iconSubY >> 8);
        updateIcon();
    }

    /**
     * ROM {@code PowerUp} (Obj2E) is {@code jsr Pow_Index / bra.w DisplaySprite}
     * (docs/s1disasm/_incObj/"26, 2E Monitors and Power-Ups.asm":217-222) — none
     * of its three routines contains an {@code out_of_range} test, a
     * {@code MarkObjGone} or a {@code RememberState}. Its ONLY exit is
     * {@code Pow_Delete}'s {@code subq.w #1,obTimeFrame / bmi.w DeleteObject}
     * (asm:402-410, the FixBugs=0 branch), so the icon holds its SST slot for
     * the full rise-plus-half-second regardless of where the camera goes.
     * <p>
     * The shared camera-distance unload therefore has no ROM counterpart here,
     * and it fired: a monitor broken as the camera scrolled away had its icon
     * freed one frame BEFORE {@code Pow_Checks} would have run, so the contents
     * were never awarded and the slot was released early (SLZ1 f5611: ROM 27
     * rings, engine 17 — the ten-ring monitor's icon in slot 39 stopped
     * updating at {@code y_vel = -$18}, one step short of the apex).
     * Same shape as Obj5C {@code Pyl_Display}.
     */
    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        return false;
    }

    @Override
    protected void applyPowerup(PlayableEntity player) {
        Sonic1MonitorObjectInstance.applyMonitorPowerup(subtype, player, services());
    }

    @Override
    protected void onIconDeactivated() {
        setDestroyed(true);
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
