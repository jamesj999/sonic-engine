package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * MTZ Long Platform Cog (Object 0x65 child) - Animated cog attached to or associated with a long platform.
 * <p>
 * Two modes:
 * <ul>
 *   <li><b>Child cog</b> (routine 4, loc_26EA4): Spawned by parent platform when subtype bit 7 is set.
 *       Reads parent's currentDist (objoff_3A) to determine animation frame.</li>
 *   <li><b>Standalone cog</b> (routine 6, loc_26EC2): Placed directly in level layout (properties index 2).
 *       Reads MTZ_Platform_Cog_X shared variable to determine animation frame.</li>
 * </ul>
 * <p>
 * Cog animation frames from byte_26EBA (s2.asm line 52727):
 * <pre>
 * Value & 7: 0->frame 0, 1->frame 0, 2->frame 2, 3->frame 2,
 *            4->frame 2, 5->frame 1, 6->frame 1, 7->frame 1
 * </pre>
 * <p>
 * Disassembly Reference: s2.asm lines 52718-52741
 */
public class MTZLongPlatformCogInstance extends AbstractObjectInstance implements RewindRecreatable {

    // byte_26EBA: cog animation frame lookup (s2.asm line 52727)
    private static final int[] COG_FRAMES = {0, 0, 2, 2, 2, 1, 1, 1};

    private int x;
    private int y;
    private boolean xFlip;

    // Parent reference (null for standalone cogs)
    @RewindTransient(reason = "Structural parent link; relinked to the matching live MTZ long-platform parent")
    private final MTZLongPlatformObjectInstance parent;

    // Whether this is a standalone cog (reads MTZ_Platform_Cog_X) vs child (reads parent)
    private boolean standalone;

    private int mappingFrame;

    /**
     * Creates a child cog spawned by a parent platform.
     */
    public MTZLongPlatformCogInstance(int x, int y, boolean xFlip,
                                      MTZLongPlatformObjectInstance parent) {
        super(createSpawn(x, y), "MTZCog");
        this.x = x;
        this.y = y;
        this.xFlip = xFlip;
        this.parent = parent;
        this.standalone = false;
        this.mappingFrame = 0;
    }

    /**
     * Creates a standalone cog placed directly in level layout.
     */
    public MTZLongPlatformCogInstance(ObjectSpawn spawn) {
        super(spawn, "MTZCogStandalone");
        this.x = spawn.x();
        this.y = spawn.y();
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.parent = null;
        this.standalone = true;
        this.mappingFrame = 0;
    }

    @Override
    public MTZLongPlatformCogInstance recreateForRewind(RewindRecreateContext ctx) {
        if (isStandaloneCogSpawn(ctx.spawn())) {
            return new MTZLongPlatformCogInstance(ctx.spawn());
        }
        MTZLongPlatformObjectInstance parent = findMatchingParent(ctx, ctx.spawn());
        if (parent == null) {
            return null;
        }
        return new MTZLongPlatformCogInstance(
                ctx.spawn().x(),
                ctx.spawn().y(),
                expectedChildXFlip(parent.getSpawn()),
                parent);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        int d0;
        if (standalone) {
            // Routine 6 (loc_26EC2): read MTZ_Platform_Cog_X
            d0 = MTZLongPlatformObjectInstance.getMtzPlatformCogX();
        } else {
            // Routine 4 (loc_26EA4): read parent's currentDist (objoff_3A)
            d0 = parent != null ? parent.getCurrentDist() : 0;
        }

        // s2.asm lines 52723-52724: andi.w #7,d0; move.b byte_26EBA(pc,d0.w),mapping_frame
        mappingFrame = COG_FRAMES[d0 & 7];

        // ROM cog tail (JmpTo19_MarkObjGone, s2.asm:52729) marks the cog gone via the
        // coarse-camera spawn window. The shared ObjectManager despawn path
        // (despawnOutOfRangeObjects / isWithinSpawnWindow, ((spawnX & 0xFF80) -
        // Camera_X_pos_coarse) unsigned > 0x280) already covers every dynamic object,
        // so no per-object DeleteObject is added here.
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = null;
        if (renderManager != null) {
            renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_COG);
        }
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(mappingFrame, x, y, xFlip, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw small cross at cog position
        ctx.drawCross(x, y, 8, 0.9f, 0.6f, 0.2f);
    }

    private static ObjectSpawn createSpawn(int x, int y) {
        return new ObjectSpawn(x, y, 0x65, 0, 0, false, 0);
    }

    private static MTZLongPlatformObjectInstance findMatchingParent(
            RewindRecreateContext ctx, ObjectSpawn childSpawn) {
        ObjectManager objectManager = ctx.objectManager();
        if (objectManager == null && ctx.objectServices() != null) {
            objectManager = ctx.objectServices().objectManager();
        }
        if (objectManager == null) {
            return null;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof MTZLongPlatformObjectInstance candidate
                    && !candidate.isDestroyed()
                    && expectedChildX(candidate.getSpawn()) == childSpawn.x()
                    && expectedChildY(candidate.getSpawn()) == childSpawn.y()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isStandaloneCogSpawn(ObjectSpawn spawn) {
        return propsIndex(spawn) == 2;
    }

    private static int propsIndex(ObjectSpawn spawn) {
        return ((spawn.subtype() >> 2) & 0x1C) >> 2;
    }

    private static int expectedChildX(ObjectSpawn parentSpawn) {
        int childX = parentSpawn.x() - 0x4C;
        if ((parentSpawn.renderFlags() & 0x01) == 0) {
            childX += 0x18;
        }
        return childX;
    }

    private static int expectedChildY(ObjectSpawn parentSpawn) {
        return parentSpawn.y() + 0x14;
    }

    private static boolean expectedChildXFlip(ObjectSpawn parentSpawn) {
        return (parentSpawn.renderFlags() & 0x01) == 0;
    }
}
