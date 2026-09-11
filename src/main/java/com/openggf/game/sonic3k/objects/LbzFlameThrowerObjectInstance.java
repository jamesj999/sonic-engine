package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * S3K S3KL object $16 - Launch Base flame thrower.
 *
 * <p>ROM reference: {@code Obj_LBZFlameThrower} ({@code sonic3k.asm:52053-52104}).
 * The parent is a static full solid object and periodically allocates an
 * {@code Obj_AutoSpin460} flame child when
 * {@code (lowByte(V_int_run_count) + subtype) & $7F == 0}.
 */
public final class LbzFlameThrowerObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_LBZFlameThrower} is installed from the S3K object pointer table at
     * {@code $000263D2} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:52058).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }

    private static final int WIDTH_PIXELS = 0x10;       // sub_263AA: width_pixels=$10
    private static final int HEIGHT_PIXELS = 0x10;      // sub_263AA: height_pixels=$10
    private static final int SOLID_HALF_WIDTH = WIDTH_PIXELS + 0x0B;
    private static final int SOLID_GROUND_HALF_HEIGHT = HEIGHT_PIXELS + 1;
    private static final int SPAWN_PERIOD_MASK = 0x7F;
    private static final int FLAME_X_OFFSET = 0x40;
    private static final int PRIORITY_BUCKET = 3;       // sub_263AA: priority=$200
    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(SOLID_HALF_WIDTH, HEIGHT_PIXELS, SOLID_GROUND_HALF_HEIGHT);

    private int subtype;
    private boolean hFlip;

    public LbzFlameThrowerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LBZFlameThrower");
        this.subtype = spawn.subtype();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        int vIntRunCounter = services().resolveVIntRunCount(vIntRunCount);
        if (((vIntRunCounter + subtype) & SPAWN_PERIOD_MASK) != 0) {
            return;
        }

        int flameX = spawn.x() + (hFlip ? -FLAME_X_OFFSET : FLAME_X_OFFSET);
        ObjectSpawn flameSpawn = new ObjectSpawn(
                flameX,
                spawn.y(),
                Sonic3kObjectIds.LBZ_FLAME_THROWER,
                0,
                hFlip ? 1 : 0,
                false,
                0);
        spawnChild(() -> new LbzFlameThrowerFlameInstance(flameSpawn));

        if (isOnScreen()) {
            services().playSfx(Sonic3kSfx.FIRE_ATTACK.id);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_FLAME_THROWER);
        if (renderer != null) {
            renderer.drawFrameIndex(0, spawn.x(), spawn.y(), hFlip, false);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj16 reaches SolidObject_cont through SolidObjectFull. Its unsigned
        // broad-X gate uses `bhi`, so relX == d1*2 is a valid zero-distance
        // side contact (sonic3k.asm:52098-52108, 41394-41401).
        return true;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HEIGHT_PIXELS;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    public String getArtKeyForTesting() {
        return Sonic3kObjectArtKeys.LBZ_FLAME_THROWER;
    }
}
