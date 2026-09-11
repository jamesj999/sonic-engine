package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;

import java.util.List;

/**
 * S3K S3KL object $21 - Launch Base Zone Act 2 gate laser.
 *
 * <p>ROM reference: {@code Obj_LBZGateLaser} ({@code sonic3k.asm:56954-57029}).
 * The resident parent owns a subtype-derived timer and periodically allocates
 * two falling laser halves. The second half is the harmful $98 touch object.
 */
public final class LbzGateLaserObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int WIDTH_PIXELS = 0x1C;
    private static final int HEIGHT_PIXELS = 0x04;
    private static final int BASE_RENDER_FLAGS = 0x04;
    private static final int PARENT_PRIORITY_BUCKET = 3; // ROM priority $180.
    private static final int HURT_PRIORITY_BUCKET = 1;   // ROM priority $80 on the second child.
    private static final int SAFE_MAPPING_FRAME = 1;
    private static final int HURT_MAPPING_FRAME = 2;
    private static final int HURT_COLLISION_FLAGS = 0x98;

    private int targetY;
    private int spawnPeriod;
    private int spawnTimer;
    private int renderFlags = BASE_RENDER_FLAGS;

    public LbzGateLaserObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LBZGateLaser");
        int subtype = spawn.subtype() & 0xFF;
        targetY = spawn.y() + ((subtype & 0x0F) << 3);
        spawnPeriod = ((subtype >> 1) & 0x78) + 8;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }

        // ROM loc_29386 decrements $30 before checking bpl, so a fresh object
        // with timer 0 fires on its first update.
        spawnTimer--;
        if (spawnTimer >= 0) {
            return;
        }

        spawnTimer = spawnPeriod;
        spawnChild(() -> new LbzGateLaserBeamInstance(
                buildSpawnAt(spawn.x(), spawn.y()),
                spawn.x(),
                spawn.y(),
                targetY,
                renderFlags,
                SAFE_MAPPING_FRAME,
                0,
                PARENT_PRIORITY_BUCKET));
        spawnChild(() -> new LbzGateLaserBeamInstance(
                buildSpawnAt(spawn.x(), spawn.y()),
                spawn.x(),
                spawn.y(),
                targetY,
                renderFlags,
                HURT_MAPPING_FRAME,
                HURT_COLLISION_FLAGS,
                HURT_PRIORITY_BUCKET));
        services().playSfx(Sonic3kSfx.ENERGY_ZAP.id);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Parent only allocates beam children, then runs Delete_Sprite_If_Not_In_Range.
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HEIGHT_PIXELS;
    }

    public int spawnTimerForTesting() {
        return spawnTimer;
    }
}
