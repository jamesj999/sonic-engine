package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;

import java.util.List;

/** Separate top-button SST slot created by {@code Obj_EggCapsule}. */
public final class HczEndBossEggCapsuleButton extends AbstractObjectInstance
        implements SolidObjectProvider, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_EggCapsule} is installed from the S3K object pointer table at
     * {@code $00086540} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:181501).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0008}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0008;
    }

    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x1B, 4, 6);

    private final HczEndBossEggCapsuleInstance parent;

    public HczEndBossEggCapsuleButton(HczEndBossEggCapsuleInstance parent, int x, int y) {
        super(new ObjectSpawn(x, y, Sonic3kObjectIds.EGG_CAPSULE, 0, 0, false, y),
                "HCZEggCapsuleButton");
        this.parent = parent;
    }

    @Override
    public HczEndBossEggCapsuleButton recreateForRewind(RewindRecreateContext ctx) {
        HczEndBossEggCapsuleInstance restoredParent = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, HczEndBossEggCapsuleInstance.class);
        return restoredParent == null ? null : new HczEndBossEggCapsuleButton(
                restoredParent, ctx.spawn().x(), ctx.spawn().y());
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public boolean allowsObjectControlledSolidContacts() {
        // SolidObjectFull_1P handles an existing standing bit before its later
        // signed object_control test, so Set_PlayerEndingPose must not clear the
        // rider. New contacts still reject bit-7 control below.
        return true;
    }

    @Override
    public boolean rejectsBit7ObjectControlNewSolidContact(PlayableEntity player) {
        return true;
    }

    @Override
    public boolean preservesObjectManagedRideWhileNotSolidFor(PlayableEntity player) {
        // The standing-bit branch precedes SolidObject_cont's signed
        // object_control rejection. Preserve only an already-established ride;
        // the new-contact path remains blocked by the predicate above.
        return player.isObjectControlled();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        var batch = checkpointAll();
        if (hasStandingContact(batch)) {
            parent.signalButtonPressed();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
    }
}
