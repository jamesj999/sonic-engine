package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import java.util.List;

/**
 * Bridge subsprite segment: a real SST occupant allocated by {@code Obj11_Init}
 * via {@code Obj11_MakeBdgSegment} (docs/s2disasm/s2.asm:21966-21988 and the
 * subroutine at :21991-22009).
 *
 * <p>The ROM bridge is not a single object. {@code Obj11_Init} calls
 * {@code Obj11_MakeBdgSegment} once unconditionally with {@code d1 = 8}
 * ({@code move.w #8,d1}, s2.asm:21969-21970) and a second time when
 * {@code subtype - 8 > 0} ({@code subq.w #8,d1 / bls.s +}, s2.asm:21975-21978),
 * so every EHZ/HPZ bridge occupies one or two object slots <em>in addition</em>
 * to the parent. Each child is allocated by
 * {@code JmpTo_AllocateObjectAfterCurrent} (s2.asm:21992), i.e. the first free
 * slot strictly after the parent's (s2.asm:33705-33724), and inherits the
 * parent's object id with {@code _move.b id(a0),id(a1)} (s2.asm:21994) — the
 * children read back as object {@code $11} exactly like the parent.
 *
 * <p>That occupancy is observable gameplay state, not a rendering detail:
 * {@code TailsCPU_CheckDespawn} (s2.asm:39408-39434) reloads
 * {@code (Tails_interact_ID).w} as an SST slot index and compares
 * {@code id(a3)} against the id Tails was riding. When Tails lands on a bridge
 * the slot he records is one of these children's, so an engine that never
 * allocated them reads an empty slot and despawns him where the ROM does not.
 *
 * <p>Execution: {@code Obj11}'s entry point tests
 * {@code render_flags.multi_sprite} first and, when set, skips the routine
 * table entirely and only draws (s2.asm:21918-21928). The bit is set on the
 * child by {@code bset #render_flags.multi_sprite,render_flags(a1)}
 * (s2.asm:21996), so a child never re-runs {@code Obj11_Init} and never
 * allocates grandchildren. This class mirrors that: it holds SST state and is
 * inert per frame.
 *
 * <p><b>Deliberate scope limit.</b> The ROM child also carries the subsprite
 * table ({@code mainspr_width = $40}, {@code mainspr_childsprites = d1}, and
 * the {@code subspr_data} x/y/mapframe triples written at s2.asm:21997-22008)
 * and is what physically draws the logs. The engine draws every log from
 * {@link BridgeObjectInstance#appendRenderCommands} and keeps doing so, because
 * the ROM's first segment is always given 8 child sprites regardless of a
 * smaller subtype while the engine renders exactly {@code subtype} logs;
 * moving the draw here would change pixels for short bridges as a side effect
 * of a slot-occupancy fix. The ROM subsprite scalars are still carried on this
 * object so the SST it models is not a fiction, and the parent's draw is
 * unchanged.
 */
public class BridgeSegmentObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    /** {@code Obj11_MakeBdgSegment}: {@code move.b #$40,mainspr_width(a1)} (s2.asm:21997). */
    private static final int ROM_MAINSPR_WIDTH = 0x40;

    /**
     * Parent bridge. Not captured: the link is rebuilt by
     * {@link #recreateForRewind}, which locates the live parent from the
     * captured {@code firstLogX}/{@code segmentY} scalars and re-registers
     * itself through {@link BridgeObjectInstance#adoptSegmentForRewind} — the
     * established parent-lookup relink used by
     * {@code EggPrisonObjectInstance}'s component links and
     * {@code CheckpointDongleInstance}. Capturing it as an object reference
     * instead requires a {@code RewindIdentityTable}, which the scalar-only
     * seeding every recreate path uses ({@code RewindCaptureContext.none()} in
     * {@code CompactFieldCapturer#restoreDefaultObjectSubclassScalars}) does not
     * carry.
     */
    @RewindTransient(reason = "structural Obj11 parent link is restored by parent lookup in "
            + "recreateForRewind")
    private BridgeObjectInstance parent;

    /**
     * {@code mainspr_childsprites(a1)} — {@code move.b d1,mainspr_childsprites(a1)}
     * (s2.asm:21998). Non-final so the generic scalar capturer can seed it.
     */
    private int childSpriteCount;

    /** {@code mainspr_width(a1)} (s2.asm:21997). */
    private int mainsprWidth = ROM_MAINSPR_WIDTH;

    /**
     * X of the first log this segment owns — the running {@code d3} at the top
     * of the {@code subspr_data} write loop (s2.asm:22001-22006). Kept for
     * rewind identity matching against the parent.
     */
    private int firstLogX;

    /** {@code move.w y_pos(a0),y_pos(a1)} (s2.asm:21995) / {@code d2} (s2.asm:22002). */
    private int segmentY;

    public BridgeSegmentObjectInstance(BridgeObjectInstance parent,
            int firstLogX, int segmentY, int childSpriteCount) {
        // _move.b id(a0),id(a1) (s2.asm:21994): the child reads back as object $11.
        super(new ObjectSpawn(firstLogX, segmentY, 0x11, 0, 0, false, 0), "BridgeSegment");
        this.parent = parent;
        this.firstLogX = firstLogX;
        this.segmentY = segmentY;
        this.childSpriteCount = childSpriteCount;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // s2.asm:21918-21928: with render_flags.multi_sprite set, Obj11 branches
        // past the routine table and only calls DisplaySprite3. No per-frame
        // state of its own.
        if (parent == null || parent.isDestroyed()) {
            // Defensive: the ROM child can only die through Obj11_Unload's
            // DeleteObject2 (s2.asm:22069-22075). If the parent left the live
            // set without running it, do not outlive the object that owns us.
            setDestroyed(true);
        }
    }

    /**
     * The ROM child has no out-of-range path at all: {@code Obj11}'s
     * {@code multi_sprite} branch (s2.asm:21918-21928) never reaches
     * {@code Obj11_Unload}, so the only thing that ever deletes a segment is the
     * parent's {@code DeleteObject2} pair (s2.asm:22069-22075). Marking the
     * segment persistent keeps the engine's counter-based unload from retiring
     * it independently and stranding the parent's {@code Obj11_child1/2}
     * pointers.
     */
    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // See the class comment: BridgeObjectInstance still draws every log.
    }

    @Override
    public int getPriorityBucket() {
        // s2.asm:21927 draws the child with object_display_list_size*3, the same
        // bucket as the parent's move.b #3,priority(a0) (s2.asm:21939).
        return RenderPriority.clamp(3);
    }

    public BridgeObjectInstance getParentBridge() {
        return parent;
    }

    public int getChildSpriteCount() {
        return childSpriteCount;
    }

    public int getMainsprWidth() {
        return mainsprWidth;
    }

    int getFirstLogX() {
        return firstLogX;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        seedCapturedScalars(ctx);
        BridgeObjectInstance restoredParent = findParentForRewind(ctx, firstLogX, segmentY);
        if (restoredParent == null) {
            return null;
        }
        BridgeSegmentObjectInstance restored =
                new BridgeSegmentObjectInstance(restoredParent, firstLogX, segmentY, childSpriteCount);
        restoredParent.adoptSegmentForRewind(restored);
        return restored;
    }

    private void seedCapturedScalars(RewindRecreateContext ctx) {
        if (ctx == null || ctx.state() == null || ctx.state().compactGenericState() == null) {
            return;
        }
        GenericFieldCapturer.restoreObjectSubclassScalarsCompact(this, ctx.state().compactGenericState());
    }

    private static BridgeObjectInstance findParentForRewind(
            RewindRecreateContext ctx, int capturedFirstLogX, int capturedSegmentY) {
        if (ctx == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectServices().objectManager();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof BridgeObjectInstance bridge) || bridge.isDestroyed()) {
                continue;
            }
            if (bridge.getSpawn().y() != capturedSegmentY) {
                continue;
            }
            if (bridge.acceptsRewindSegmentAt(capturedFirstLogX)) {
                return bridge;
            }
        }
        return null;
    }
}
