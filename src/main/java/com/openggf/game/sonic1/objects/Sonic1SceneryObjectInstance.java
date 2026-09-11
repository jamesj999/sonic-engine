package com.openggf.game.sonic1.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Sonic 1 Scenery - Object ID 0x1C.
 * <p>
 * A purely decorative static object with no collision, no animation, and no movement.
 * Renders a single sprite frame based on subtype:
 * <ul>
 *   <li>Subtypes 0-2: SLZ fireball launcher base (Map_Scen frame 0, 16x32 px)</li>
 *   <li>Subtype 3: GHZ bridge stump (Map_Bri frame 1, 32x16 px)</li>
 * </ul>
 * <p>
 * From disassembly: Scen_Values table provides per-subtype configuration:
 * mappings, art tile, frame, width, priority, collision (always 0).
 * Object only has two routines: init (Scen_Main) and display/delete (Scen_ChkDel).
 * <p>
 * Reference: docs/s1disasm/_incObj/1C Scenery.asm
 */
public class Sonic1SceneryObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // From Scen_Values: subtype 3 uses Map_Bri frame 1 (bridge stump)
    private static final int SUBTYPE_BRIDGE_STUMP = 3;

    // Art key and frame index resolved from subtype
    private final String artKey;
    private int frameIndex;

    public Sonic1SceneryObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Scenery");

        int subtype = spawn.subtype() & 0xFF;
        if (subtype == SUBTYPE_BRIDGE_STUMP) {
            // Subtype 3: GHZ bridge stump - uses Map_Bri, frame 1
            // From Scen_Values: dc.l Map_Bri / make_art_tile(ArtTile_GHZ_Bridge,2,0) / frame=1
            artKey = ObjectArtKeys.BRIDGE;
            frameIndex = 1;
        } else {
            // Subtypes 0-2: SLZ fireball launcher - uses Map_Scen, frame 0
            // From Scen_Values: dc.l Map_Scen / make_art_tile(ArtTile_SLZ_Fireball_Launcher,2,0) / frame=0
            artKey = ObjectArtKeys.SCENERY;
            frameIndex = 0;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer == null) return;
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        renderer.drawFrameIndex(frameIndex, getX(), getY(), hFlip, false);
    }
}
