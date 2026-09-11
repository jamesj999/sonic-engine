package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * MCZ Boss falling debris - stones and spikes spawned during descent phases.
 * ROM Reference: Obj57_FallingStuff (s2.asm:65856-65861)
 *
 * Stones: frame 0x0D (13) in Obj57_MapUnc_316EC, no collision hazard
 * Spikes: frame 0x14 (20) in Obj57_MapUnc_316EC, collision_flags 0xB1 (hazard)
 *
 * Uses ObjectMoveAndFall for gravity. Deleted when Y >= 0x6F0.
 */
public class MCZFallingDebrisInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnCoordinateZeroScalarArgsRewindRecreatable {

    // ROM: ObjectMoveAndFall adds $38 to y_vel, then subi.w #$28,y_vel(a0)
    // sub2_y_pos at SST offset $12 aliases y_vel. Net gravity = $38 - $28 = $10
    private static final int GRAVITY = 0x10;
    private static final int DELETE_Y = 0x6F0;

    // ROM mapping frame indices in Obj57_MapUnc_316EC
    private static final int FRAME_STONE = 0x0D; // Map_obj57_014C: 2x2 rock
    private static final int FRAME_SPIKE = 0x14; // Map_obj57_01C2: 1x4 stalactite

    // Non-final so GenericFieldCapturer captures/restores it across held rewind: the
    // recreate hook uses a placeholder (false) and the captured stone/spike value is reapplied
    // on restore (the ObjectSpawn carries no spike bit, so it is not spawn-derivable).
    private boolean isSpike;
    private int posX;
    private int posY;
    private int yFixed;
    private int yVel;

    public MCZFallingDebrisInstance(int x, int y, boolean isSpike) {
        super(new ObjectSpawn(x, y, 0x57, 4, 0, false, 0), "MCZ Debris");
        this.isSpike = isSpike;
        this.posX = x;
        this.posY = y;
        this.yFixed = y << 16;
        this.yVel = 0;
        updateDynamicSpawn(posX, posY);
    }

    MCZFallingDebrisInstance(ObjectSpawn spawn) {
        this(spawn.x(), spawn.y(), false);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: Obj57_FallingStuff - ObjectMoveAndFall
        yFixed += (yVel << 8);
        yVel += GRAVITY;

        posY = yFixed >> 16;
        updateDynamicSpawn(posX, posY);

        // ROM: cmpi.w #$6F0,y_pos(a0) - delete if below boundary
        if (posY > DELETE_Y) {
            setDestroyed(true);
        }
    }

    @Override
    public int getCollisionFlags() {
        // ROM: Spikes have collision_flags $B1 (hazard), stones have none
        return isSpike ? 0xB1 : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getPriorityBucket() {
        return 3; // ROM: move.b #3,priority(a1)
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.MCZ_FALLING_ROCKS);
        if (renderer == null) return;

        int frame = isSpike ? FRAME_SPIKE : FRAME_STONE;
        renderer.drawFrameIndex(frame, posX, posY, false, false);
    }
}
