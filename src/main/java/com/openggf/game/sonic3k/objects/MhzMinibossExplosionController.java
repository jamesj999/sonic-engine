package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;

import java.util.List;

/** Independent SST owner for loc_75DCC's CreateBossExp10 / Obj_BossExpControl2. */
final class MhzMinibossExplosionController extends AbstractObjectInstance
        implements SpawnCoordinateRewindRecreatable {
    private int remaining = 0x20;
    private int waitCounter;
    private boolean pendingDelete;

    MhzMinibossExplosionController(int x, int y) {
        super(new ObjectSpawn(x, y, 0, 0x10, 0, false, 0), "MHZMinibossExplosionControl");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (pendingDelete) {
            setDestroyed(true);
            return;
        }
        // Obj_CreateBossExplosion immediately enters Obj_Wait with zeroed $2E.
        if (--waitCounter >= 0) {
            return;
        }
        if (--remaining == 0) {
            // Go_Delete_Sprite installs next-pass deletion.
            pendingDelete = true;
            return;
        }
        waitCounter = 2;
        var manager = services().objectManager();
        if (manager == null) {
            return;
        }
        int slot = ObjectLifetimeOps.reserveFindNextFreeChildSlot(manager, getSlotIndex());
        if (slot < 0) {
            return; // The attempt is consumed; no RNG or child-init sound on failure.
        }
        try {
            var child = ObjectConstructionContext.with(services(), slot,
                    () -> S3kBossExplosionChild.createWithNativeInitSfx(getX(), getY()));
            // sub_83E90 uses the low word for X, then SWAP for Y, after allocation.
            int random = services().rng().nextRaw();
            child.writeNativePositionWords(getX() + (random & 0x3F) - 0x20,
                    getY() + ((random >>> 16) & 0x3F) - 0x20);
            ObjectLifetimeOps.addDynamicAtReservedSlot(manager, child, slot);
        } catch (RuntimeException | Error failure) {
            manager.releaseDynamicSlot(slot);
            throw failure;
        }
    }

    @Override
    public boolean isPersistent() {
        // This controller has no camera-distance or parent-liveness gate.
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) { }
}
