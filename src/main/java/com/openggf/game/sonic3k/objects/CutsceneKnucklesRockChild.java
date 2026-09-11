package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Breakable rock child spawned by CutsceneKnucklesAiz1Instance.
 * ROM: loc_61F60 (sonic3k.asm:128755)
 *
 * Phase 1 (Init/Wait): Spawned by Knuckles init routine. Draws itself each
 * frame and polls the parent's status bit 7 (triggered flag).
 *
 * Phase 2 (Break): When the parent is triggered, increments mapping_frame,
 * calls BreakObjectToPieces to scatter fragment sprites, and deletes self.
 */
public class CutsceneKnucklesRockChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(CutsceneKnucklesRockChild.class.getName());

    /** Parent Knuckles object whose triggered flag we poll. */
    private final CutsceneKnucklesAiz1Instance parent;

    /** Current mapping frame index. Incremented on break. */
    private int mappingFrame;

    /** Whether the rock has already been broken. */
    private boolean broken;

    public CutsceneKnucklesRockChild(ObjectSpawn spawn, CutsceneKnucklesAiz1Instance parent) {
        super(spawn, "CutsceneKnuxRock");
        this.parent = parent;
        this.mappingFrame = 0;
        this.broken = false;
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CutsceneKnucklesAiz1Instance liveParent = findLiveParent(ctx);
        if (liveParent == null) {
            return null;
        }
        return new CutsceneKnucklesRockChild(ctx.spawn(), liveParent);
    }

    private static CutsceneKnucklesAiz1Instance findLiveParent(RewindRecreateContext ctx) {
        ObjectSpawn childSpawn = ctx.spawn();
        for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
            if (object instanceof CutsceneKnucklesAiz1Instance parent
                    && !parent.isDestroyed()
                    && parent.getSpawn().x() == childSpawn.x()
                    && parent.getSpawn().y() + 0x20 == childSpawn.y()) {
                return parent;
            }
        }
        for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
            if (object instanceof CutsceneKnucklesAiz1Instance parent && !parent.isDestroyed()) {
                return parent;
            }
        }
        return null;
    }

    @Override
    public int getPriorityBucket() {
        return 3; // ROM priority 0x180
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken || isDestroyed()) {
            return;
        }

        // Phase 1: poll parent's triggered flag each frame
        if (parent != null && parent.isTriggered()) {
            // Phase 2: break apart
            mappingFrame++;
            broken = true;
            LOG.fine("Rock child: breaking apart (mapping_frame=" + mappingFrame + ")");

            // ROM: BreakObjectToPieces plays sfx_Collapse (0x59) as its first action
            try {
                services().playSfx(Sonic3kSfx.COLLAPSE.id);
            } catch (Exception e) {
                LOG.fine(() -> "CutsceneKnucklesRockChild.update: " + e.getMessage());
            }

            // BreakObjectToPieces: spawn fragments with scattered velocities
            spawnFragments();
            setDestroyed(true);
        }
    }

    /**
     * Spawns rock fragment children with scattered velocities from
     * ROM word_2A8B0 (12 entries), implementing BreakObjectToPieces.
     *
     * ROM (sonic3k.asm:45772): each fragment gets a unique mapping piece
     * from the parent's broken frame (frame 1 has 12 pieces). Piece index
     * matches the velocity table index (0-11).
     */
    private void spawnFragments() {
        try {
            int[][] velocities = AizRockFragmentChild.FRAGMENT_VELOCITIES;
            for (int i = 0; i < velocities.length; i++) {
                int fragmentIndex = i;
                ObjectSpawn fragSpawn = new ObjectSpawn(
                        getX(), getY(), 0, 0, 0, false, 0);
                AizRockFragmentChild frag = spawnFreeChild(() -> new AizRockFragmentChild(
                        fragSpawn, velocities[fragmentIndex][0], velocities[fragmentIndex][1],
                        mappingFrame, fragmentIndex));
                // Compensate for pendingDynamicAdditions delay: ROM processes
                // each fragment's first movement in the same frame as creation.
                frag.update(0, null);
            }
        } catch (Exception e) {
            LOG.fine("Could not spawn rock fragments (test env?): " + e.getMessage());
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = AizIntroArtLoader.getCorkFloorRenderer(services());
        if (renderer == null || !renderer.isReady()) return;
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
    }

    // -----------------------------------------------------------------------
    // Accessors (for testing / external use)
    // -----------------------------------------------------------------------

    public int getMappingFrame() {
        return mappingFrame;
    }

    public boolean isBroken() {
        return broken;
    }
}
