package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractSpikeObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

public class SpikeObjectInstance extends AbstractSpikeObjectInstance implements RewindRecreatable {
    private boolean mainRoutineReached;

    public SpikeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    @Override
    public SpikeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SpikeObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!mainRoutineReached) {
            // Obj36_Init initializes the routine, dimensions, and saved origin,
            // then returns through Adjust2PArtPointer without calling MoveSpikes
            // (docs/s2disasm/s2.asm:29362-29389). The first +8/-8 movement step
            // belongs to the next object execution in Obj36_Upright/Sideways.
            mainRoutineReached = true;
            return;
        }
        super.update(vIntRunCount, player);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        int frameIndex = Math.clamp((spawn.subtype() >> 4) & 0xF, 0, 7);
        boolean sideways = frameIndex >= 4;
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        PatternSpriteRenderer renderer = renderManager.getSpikeRenderer(sideways);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(frameIndex, currentX, currentY, hFlip, vFlip);
        }
    }

    @Override
    protected void playSpikeMoveSfx() {
        if (!isOnScreen()) {
            return;
        }
        try {
            services().playSfx(Sonic2Sfx.SPIKES_MOVE.id);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic.
        }
    }

    @Override
    public boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(PlayableEntity player) {
        // S2 Obj36 calls SolidObject, whose lower reject bound doubles the live
        // y_radius(a1), so rolling players use the smaller rolling radius on
        // both halves (docs/s2disasm/s2.asm:35156-35169).
        return true;
    }

}
