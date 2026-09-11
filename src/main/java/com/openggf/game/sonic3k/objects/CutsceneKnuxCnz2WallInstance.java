package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;

import java.util.List;

/**
 * Invisible blocking wall spawned by the first CNZ Act 2 Knuckles cutscene.
 *
 * <p>ROM reference: {@code CutsceneKnux_CNZ2A} creates this as {@code ChildObjDat_66560}
 * during its init routine ({@code loc_622E4}); the child runs {@code loc_62458}
 * (docs/skdisasm/sonic3k.asm:129076, 129175, 134968). Every frame the child calls
 * {@code SolidObjectFull2} with {@code d1=$13} (half-width) and {@code d2=$100}
 * (half-height) so Sonic cannot run past Knuckles during the cutscene. The child
 * deletes itself once the parent sets its destroyed status bit
 * ({@code btst #7,status(a1) -> CutsceneKnux_Delete}).
 *
 * <p>{@code loc_62458} routes through {@code SolidObjectFull2_1P}, which falls
 * directly into {@code SolidObject_cont} without the {@code SolidObjectFull_1P}
 * on-screen gate and treats {@code relX == width*2} as a contact via {@code bhi}
 * (sonic3k.asm:41051-41089, 41394-41401). Those translate to
 * {@link #bypassesOffscreenSolidGate()} and {@link #usesInclusiveRightEdge()}.
 */
public final class CutsceneKnuxCnz2WallInstance extends AbstractObjectInstance
        implements SolidObjectProvider, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code loc_62458} is the routine this object runs, i.e. address
     * {@code $00062458} (docs/skdisasm/sonic3k.asm:129180).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0006}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0006;
    }


    // ROM loc_62458: moveq #$13,d1 (half-width); move.w #$100,d2 / move.w #$200,d3.
    // The tall vertical extent makes the barrier impassable across the whole
    // cutscene play area; only the side push matters at ground level.
    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(0x13, 0x100, 0x200);

    private final ObjectInstance owner;

    public CutsceneKnuxCnz2WallInstance(ObjectSpawn spawn, ObjectInstance owner) {
        super(spawn, "CutsceneKnuxCNZ2Wall");
        this.owner = owner;
        if (owner instanceof CutsceneKnucklesCnz2AInstance parent) {
            parent.rewindAttachBlockingWall(this);
        }
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CutsceneKnucklesCnz2AInstance liveParent = findLiveParent(ctx);
        if (liveParent == null) {
            return null;
        }
        return new CutsceneKnuxCnz2WallInstance(ctx.spawn(), liveParent);
    }

    private static CutsceneKnucklesCnz2AInstance findLiveParent(RewindRecreateContext ctx) {
        ObjectSpawn wallSpawn = ctx.spawn();
        for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
            if (object instanceof CutsceneKnucklesCnz2AInstance parent
                    && !parent.isDestroyed()
                    && parent.getSpawn().x() - 0x20 == wallSpawn.x()
                    && parent.getSpawn().y() - 0x6C == wallSpawn.y()) {
                return parent;
            }
        }
        for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
            if (object instanceof CutsceneKnucklesCnz2AInstance parent && !parent.isDestroyed()) {
                return parent;
            }
        }
        return null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM loc_62458: btst #7,status(parent) -> CutsceneKnux_Delete. The wall
        // lives exactly as long as the cutscene parent.
        if (owner != null && owner.isDestroyed()) {
            setDestroyed(true);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        return true;
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        return true;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible barrier.
    }
}
