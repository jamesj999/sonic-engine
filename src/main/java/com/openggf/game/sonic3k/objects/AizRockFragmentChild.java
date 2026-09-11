package com.openggf.game.sonic3k.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Lightweight gravity-affected rock fragment for the BreakObjectToPieces effect.
 * Spawned by {@link CutsceneKnucklesRockChild} when the rock breaks apart.
 *
 * Each fragment receives a scattered velocity from a ROM velocity table
 * and falls with gravity until offscreen, then deletes itself.
 *
 * ROM: BreakObjectToPieces (sonic3k.asm:45772) gives each fragment a unique
 * mapping piece from the parent's broken frame. The cork floor's frame 1 has
 * 12 pieces, one per fragment. Each fragment renders only its assigned piece
 * via {@code drawFramePieceByIndex}.
 *
 * ROM also sets high_priority on fragment art tiles (ori.w #high_priority),
 * so fragments render in front of high-priority FG tiles.
 */
public class AizRockFragmentChild extends GravityDebrisChild
        implements SpawnTrailingZeroIntsRewindRecreatable {

    /**
     * Fragment-specific gravity: 0x18 subpixels/frame.
     * ROM (sonic3k.asm:58588): loc_2A5F8 uses MoveSprite2 (no gravity) then
     * {@code addi.w #$18,y_vel(a0)} — much lighter than standard S3K gravity (0x38).
     */
    private static final int GRAVITY = 0x18;

    /**
     * ROM velocity table word_2A8B0 for 12 fragments (BreakObjectToPieces).
     * Each entry: {xVel, yVel} in subpixels. 6 symmetric pairs.
     */
    static final int[][] FRAGMENT_VELOCITIES = {
            { -0x100, -0x200 }, {  0x100, -0x200 },
            {  -0xE0, -0x1C0 }, {   0xE0, -0x1C0 },
            {  -0xC0, -0x180 }, {   0xC0, -0x180 },
            {  -0xA0, -0x140 }, {   0xA0, -0x140 },
            {  -0x80, -0x100 }, {   0x80, -0x100 },
            {  -0x60,  -0xC0 }, {   0x60,  -0xC0 },
    };

    // Non-final so the generic field capturer reapplies them after a rewind
    // recreate (passed-in differentiators not recoverable from the ObjectSpawn).
    private int mappingFrame;
    private int pieceIndex;

    AizRockFragmentChild() {
        this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), 0, 0, 0, 0);
    }

    public AizRockFragmentChild(ObjectSpawn spawn, int xVel, int yVel, int mappingFrame, int pieceIndex) {
        super(spawn, "RockFragment", xVel, yVel, GRAVITY);
        this.mappingFrame = mappingFrame;
        this.pieceIndex = pieceIndex;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = AizIntroArtLoader.getCorkFloorRenderer(services());
        if (renderer == null || !renderer.isReady()) return;
        renderer.drawFramePieceByIndex(mappingFrame, pieceIndex, motionState.x, motionState.y, false, false);
    }
}
