package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0xBB - ICZ ice block.
 *
 * <p>ROM reference: {@code Obj_ICZIceBlock} at sonic3k.asm:187768-187790.
 * The block applies {@code ObjDat_ICZIceBlock} and runs {@code SolidObjectTop}
 * every frame with {@code d1=$1B}, {@code d2=$10}, {@code d3=$11}.
 */
public class IczIceBlockObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable, SolidObjectProvider, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_ICZIceBlock} is installed from the S3K object pointer table at
     * {@code $0008A330} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:187773).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0008}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0008;
    }

    private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS_MISC2;
    private static final int PRIORITY_BUCKET = 5; // ObjDat_ICZIceBlock priority $280.
    private static final int MAPPING_FRAME = 0x1E;
    private static final int PALETTE_LINE = 2;

    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x1B, 0x10, 0x11);

    private int x;
    private int y;
    private boolean hFlip;

    public IczIceBlockObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZIceBlock");
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public IczIceBlockObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new IczIceBlockObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Static top-solid. ObjectManager owns MarkObjGone-style unload for layout objects.
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
    public boolean isSolidFor(PlayableEntity player) {
        return !isDestroyed();
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
    public int getOutOfRangeReferenceX() {
        return x;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(MAPPING_FRAME, x, y, hFlip, false, PALETTE_LINE);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx != null) {
            ctx.drawRect(x, y, SOLID_PARAMS.halfWidth(), SOLID_PARAMS.groundHalfHeight(),
                    0.2f, 0.8f, 1.0f);
        }
    }
}
