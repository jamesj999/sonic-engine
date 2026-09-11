package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0xEA - Pachinko platform.
 *
 * <p>ROM reference: {@code Obj_Pachinko_Platform}. Static top-solid platform using
 * {@code SolidObjectTop} with D1 = width_pixels + $0B, D2 = height_pixels,
 * D3 = height_pixels + 1.
 */
public class PachinkoPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_Pachinko_Platform} is installed from the S3K object pointer table at
     * {@code $0004A186} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:96742).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0004}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0004;
    }


    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x2B, 0x0C, 0x0D);

    public PachinkoPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "PachinkoPlatform");
    }

    @Override
    public PachinkoPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new PachinkoPlatformObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Static top-solid platform.
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.topSolid(usesStickyContactBuffer());
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.PACHINKO_PLATFORM);
        if (renderer == null) {
            return;
        }
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(0, spawn.x(), spawn.y(), hFlip, vFlip);
    }
}
