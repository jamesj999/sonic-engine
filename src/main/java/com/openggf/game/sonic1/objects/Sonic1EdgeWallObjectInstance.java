package com.openggf.game.sonic1.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * GHZ Edge Walls (Object ID 0x44).
 * <p>
 * Solid wall barriers used exclusively in Green Hill Zone. The subtype byte controls
 * both the visual frame and whether the wall provides solid collision:
 * <ul>
 *   <li>Bit 4 = 0: Solid wall (collision active)</li>
 *   <li>Bit 4 = 1: Display-only decoration (no collision)</li>
 * </ul>
 * The frame index is the subtype with bit 4 cleared:
 * <ul>
 *   <li>Frame 0: Light top + shadow body</li>
 *   <li>Frame 1: All light</li>
 *   <li>Frame 2: All dark/shadow</li>
 * </ul>
 * <p>
 * Collision dimensions from disassembly: d1 = $13 (half-width 19px), d2 = $28 (half-height 40px).
 * Uses custom Obj44_SolidWall routine which pushes player sideways (zeroing inertia + X velocity)
 * and blocks upward movement through ceiling.
 * <p>
 * Reference: docs/s1disasm/_incObj/44 GHZ Edge Walls.asm
 */
public class Sonic1EdgeWallObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable, SolidObjectProvider, SolidObjectListener {

    // From disassembly: move.w #$13,d1
    private static final int HALF_WIDTH = 0x13;

    // From disassembly: move.w #$28,d2
    private static final int HALF_HEIGHT = 0x28;

    private int frameIndex;
    private boolean solid;

    public Sonic1EdgeWallObjectInstance(ObjectSpawn spawn) {
        super(spawn, "EdgeWall");

        int subtype = spawn.subtype();
        // From disassembly: bclr #4,obFrame(a0) / beq.s Edge_Solid
        this.solid = (subtype & 0x10) == 0;
        // Frame = subtype with bit 4 cleared
        this.frameIndex = subtype & ~0x10;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.GHZ_EDGE_WALL);
        if (renderer == null) return;
        renderer.drawFrameIndex(frameIndex, getX(), getY(), false, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return solid;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No special behavior beyond standard solid collision.
        // The engine's SolidContacts system handles side pushing (zeroing inertia/velX)
        // and ceiling blocking (zeroing velY), matching Obj44_SolidWall behavior.
    }
}
