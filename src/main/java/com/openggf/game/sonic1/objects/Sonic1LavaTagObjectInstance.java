package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.objects.LavaMarkerObjectInstance;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Sonic 1 Lava Tag Object (Obj54) - Invisible lava/hazard collision marker (MZ).
 * <p>
 * Defines invisible hazard areas used in Marble Zone to mark lava regions.
 * The object damages the player on contact but has no visible sprite representation.
 * <p>
 * <b>Disassembly Reference:</b> {@code _incObj/54 Lava Tag.asm}
 * <ul>
 *   <li>{@code LTag_Main} (Routine 0): Sets collision flags from subtype lookup, loads mappings</li>
 *   <li>{@code LTag_ChkDel} (Routine 2): Only performs off-screen culling via MarkObjGone</li>
 * </ul>
 *
 * <h3>Subtypes</h3>
 * <table border="1">
 *   <tr><th>Subtype</th><th>Collision Flags</th><th>Description</th></tr>
 *   <tr><td>0</td><td>0x96</td><td>HURT | size index 0x16</td></tr>
 *   <tr><td>1</td><td>0x94</td><td>HURT | size index 0x14</td></tr>
 *   <tr><td>2</td><td>0x95</td><td>HURT | size index 0x15</td></tr>
 * </table>
 *
 * <h3>Collision Flags Encoding</h3>
 * <pre>
 * Byte format: [CC][SSSSSS]
 * - CC (bits 6-7): Category - 0x80 = HURT
 * - SSSSSS (bits 0-5): Size index into TouchResponseTable
 * </pre>
 *
 * <h3>Usage Statistics</h3>
 * Total: 48 instances (MZ1: 10, MZ2: 17, MZ3: 21)
 *
 * @see LavaMarkerObjectInstance S2 equivalent (Obj31)
 */
public class Sonic1LavaTagObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    // ========================================================================
    // ROM Constants - Collision Flags by Subtype
    // From _incObj/54 Lava Tag.asm: LTag_ColTypes dc.b $96, $94, $95
    // ========================================================================

    /**
     * Subtype 0: HURT category (0x80) | size index 0x16.
     * From disassembly: LTag_ColTypes first byte = $96
     */
    private static final int COLLISION_FLAGS_SUBTYPE_0 = 0x96;

    /**
     * Subtype 1: HURT category (0x80) | size index 0x14.
     * From disassembly: LTag_ColTypes second byte = $94
     */
    private static final int COLLISION_FLAGS_SUBTYPE_1 = 0x94;

    /**
     * Subtype 2: HURT category (0x80) | size index 0x15.
     * From disassembly: LTag_ColTypes third byte = $95
     */
    private static final int COLLISION_FLAGS_SUBTYPE_2 = 0x95;

    /**
     * Lookup table for collision flags by subtype.
     * ROM: LTag_ColTypes at _incObj/54 Lava Tag.asm line 14
     */
    private static final int[] COLLISION_FLAGS_TABLE = {
            COLLISION_FLAGS_SUBTYPE_0,  // Subtype 0
            COLLISION_FLAGS_SUBTYPE_1,  // Subtype 1
            COLLISION_FLAGS_SUBTYPE_2   // Subtype 2
    };

    /** Debug rendering color for lava/fire hazard (orange). */
    private static final DebugColor DEBUG_COLOR = new DebugColor(255, 102, 0);

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Cached collision flags based on subtype. */
    private int collisionFlags;

    /** Subtype index (0, 1, or 2), clamped to valid range. */
    private int subtypeIndex;

    public Sonic1LavaTagObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LavaTag");

        // ROM: moveq #0,d0 / move.b obSubtype(a0),d0
        // ROM: move.b LTag_ColTypes(pc,d0.w),obColType(a0)
        this.subtypeIndex = Math.min(spawn.subtype() & 0xFF, COLLISION_FLAGS_TABLE.length - 1);
        this.collisionFlags = COLLISION_FLAGS_TABLE[subtypeIndex];
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM LTag_ChkDel (Routine 2) only performs off-screen culling via MarkObjGone.
        // The ObjectManager.Placement system handles this automatically,
        // so no explicit logic is needed here.
    }

    // ========================================================================
    // TouchResponseProvider Implementation
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        // No collision property (not a boss, no hit counter)
        return 0;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible during normal gameplay.
        // ROM: Map_LTag has spriteHeader with 0 pieces (no sprites).
        // obRender = $84 (coordinate-based, on-screen flagging only).
    }

    @Override
    public int getPriorityBucket() {
        // Low priority since it is always invisible during gameplay
        return 0;
    }

    // ========================================================================
    // Debug Rendering
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw the collision area as an orange rectangle outline.
        // Size index determines the half-width/half-height from the touch response table.
        // The actual dimensions are resolved by the touch response system at runtime.
        // Here we draw a marker showing the object's position and subtype.
        int sizeIndex = collisionFlags & 0x3F;
        ctx.drawCross(spawn.x(), spawn.y(), 4, 1.0f, 0.4f, 0.0f);
        ctx.drawWorldLabel(spawn.x(), spawn.y(), -1,
                String.format("LavaTag[%d] col=0x%02X sz=0x%02X", subtypeIndex, collisionFlags, sizeIndex),
                DEBUG_COLOR);
    }
}
