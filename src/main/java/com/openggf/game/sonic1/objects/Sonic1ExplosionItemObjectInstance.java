package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateDefaultArgsRewindRecreatable;

/**
 * Sonic 1 badnik replacement explosion.
 * <p>
 * ROM {@code ExplosionItem} keeps the destroyed badnik's slot, then spawns the
 * animal from its first routine-0 update. The points popup is allocated after
 * the animal, preserving the same FindFreeObj ordering.
 */
public class Sonic1ExplosionItemObjectInstance extends ExplosionObjectInstance
        implements SpawnCoordinateDefaultArgsRewindRecreatable {
    // Un-finaled for rewind: pointsValue is NOT spawn-derivable (it is the destroyed
    // badnik's points value passed by the spawner), so the generic field capturer
    // reapplies the captured value after the recreate hook uses placeholder 0.
    private int pointsValue;
    private boolean spawnedChildren;

    public Sonic1ExplosionItemObjectInstance(int x, int y, ObjectServices services, int pointsValue) {
        super(0x27, x, y, services != null ? services.renderManager() : null);
        this.pointsValue = pointsValue;
    }

    private Sonic1ExplosionItemObjectInstance() {
        this(0, 0, null, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!spawnedChildren) {
            spawnedChildren = true;
            spawnChildren();
        }
        super.update(vIntRunCount, player);
    }

    private void spawnChildren() {
        ObjectServices svc = tryServices();
        if (svc == null) {
            return;
        }
        ObjectManager objectManager = svc.objectManager();
        if (objectManager == null) {
            return;
        }

        int x = spawn.x();
        int y = spawn.y();
        // ROM parity: ExplosionItem routine 0 only allocates Obj28 with FindFreeObj
        // and copies objoff_3E into it. Obj28 then allocates Obj29 during its own
        // routine 0 (docs/s1disasm/_incObj/24, 27 & 3F Explosions.asm:53-60;
        // docs/s1disasm/_incObj/28 Animals.asm:163-168).
        objectManager.addDynamicObject(new Sonic1AnimalsObjectInstance(
                new ObjectSpawn(x, y, 0x28, 0, 0, false, 0), pointsValue));
    }
}
