package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Shared beam object used by CNZ's late Knuckles teleporter route.
 *
 * <p>ROM anchors: {@code Obj_TeleporterBeam}, {@code Obj_TeleporterBeamWait},
 * and {@code Obj_TeleporterBeamExpand}.
 *
 * <p>Task 8 only needs the timing seam exported by the shared beam object:
 * the CNZ teleporter parent watches this counter and reacts at two verified
 * thresholds copied from the disassembly:
 * <ul>
 *   <li>{@code counter == 8}: the parent takes full object control and forces
 *   the player into a rolled transport pose</li>
 *   <li>{@code counter >= $18}: the parent hides the player, plays the
 *   transporter SFX, and treats the route as scroll-locked</li>
 * </ul>
 *
 * <p>The real beam also steps through mapping-frame choreography while waiting
 * for the last beam sprite to reach frame 2 before entering the expansion
 * phase. Task 8 deliberately exports a simple frame counter instead of full
 * sprite choreography because the parent handoff is the only contract covered
 * by the bounded slice and its headless tests.
 */
public final class CnzTeleporterBeamInstance extends AbstractObjectInstance implements RewindRecreatable {
    /**
     * Verified handoff seam: the parent checks for beam counter 8 before
     * forcing object control.
     */
    public static final int PLAYER_CAPTURE_COUNTER = 0x08;

    /**
     * Verified handoff seam: at {@code >= $18} the parent hides the player and
     * plays the transporter SFX.
     */
    public static final int PLAYER_HIDE_COUNTER = 0x18;

    /**
     * Task 8 keeps the shared beam alive a little beyond the hide threshold so
     * the parent has a stable window to observe the final transport phase.
     * This is an engine-side lifetime guard, not a claimed ROM threshold.
     */
    private static final int DESTROY_COUNTER = 0x20;

    private int centreX;
    private int centreY;
    private int beamCounter;

    public CnzTeleporterBeamInstance(ObjectSpawn spawn) {
        super(spawn, "CNZTeleporterBeam");
        this.centreX = spawn.x();
        this.centreY = spawn.y();
    }

    @Override
    public CnzTeleporterBeamInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzTeleporterBeamInstance(ctx.spawn());
    }

    /**
     * Returns the shared beam's parent-observed progress counter.
     */
    public int getBeamCounter() {
        return beamCounter;
    }

    @Override
    public int getX() {
        return centreX;
    }

    @Override
    public int getY() {
        return centreY;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        beamCounter++;
        if (beamCounter >= DESTROY_COUNTER) {
            setDestroyed(true);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_TELEPORTER);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(0, centreX, centreY, false, false);
    }
}
