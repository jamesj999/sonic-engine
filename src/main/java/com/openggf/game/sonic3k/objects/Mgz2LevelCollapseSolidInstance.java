package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.Sonic3kMGZEvents;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Invisible carrier solid for the MGZ2 boss-floor collapse.
 *
 * <p>ROM: Obj_MGZ2LevelCollapseSolid. The screen event clears the real level
 * chunks, then creates 20 of these full-solid objects so Sonic can stand on the
 * visually deforming floor columns while they drop away.
 */
public final class Mgz2LevelCollapseSolidInstance extends AbstractObjectInstance
        implements SolidObjectProvider, RomObjectCodePointerProvider, RewindRecreatable {

    private static final int OBJECT_ID = 0xFF;
    private static final int HALF_WIDTH = 0x1B;
    private static final int HALF_HEIGHT = 0x40;

    private int anchorX;
    private int baseY;
    private final IntSupplier scrollSupplier;
    private final BooleanSupplier deleteSupplier;

    Mgz2LevelCollapseSolidInstance() {
        this(0, 0, () -> 0, () -> false);
    }

    public Mgz2LevelCollapseSolidInstance(int anchorX, int baseY,
                                          IntSupplier scrollSupplier,
                                          BooleanSupplier deleteSupplier) {
        super(new ObjectSpawn(anchorX, baseY, OBJECT_ID, 0, 0, false, 0),
                "MGZ2LevelCollapseSolid");
        this.anchorX = anchorX;
        this.baseY = baseY;
        this.scrollSupplier = scrollSupplier;
        this.deleteSupplier = deleteSupplier;
        updateDynamicSpawn(anchorX, baseY);
    }

    @Override
    public int getX() {
        return anchorX;
    }

    @Override
    public int getY() {
        return baseY + scrollSupplier.getAsInt();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (deleteSupplier.getAsBoolean()) {
            setDestroyed(true);
            return;
        }
        updateDynamicSpawn(anchorX, getY());
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return !isDestroyed() && !deleteSupplier.getAsBoolean();
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj_MGZ2LevelCollapseSolid jumps to SolidObjectFull2. Its entry X
        // gate rejects only values above d1*2, so the exact right edge remains
        // eligible (sonic3k.asm:41065-41067,106955-106970).
        return true;
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // SolidObjectFull2_1P falls directly into SolidObject_cont when the
        // standing bit is clear. Unlike SolidObjectFull_1P it never tests the
        // render flag, which is essential here because this carrier is always
        // invisible (sonic3k.asm:41065-41067,106955-106970).
        return true;
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // SolidObjectFull2_1P tests its retained standing bit before entering
        // SolidObject_cont. If the rider has jumped, loc_1DCF0 clears the bit
        // and returns d4=0; it must not fall through to loc_1E154's upward
        // position lift (sonic3k.asm:41065-41084,41608-41637).
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // The ROM standing bit lives in this carrier's SST while its y_pos is
        // rewritten from the collapse scroll word every frame. The engine's
        // dynamic spawn therefore cannot be the latch key because it changes
        // along with y_pos; retain the bit on the live carrier instance.
        return true;
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // Obj_MGZ2LevelCollapseSolid runs at $0005180A in the locked-on ROM.
        return 0x0005;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public Mgz2LevelCollapseSolidInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectServices services = ctx.objectServices();
        if (services == null || !(services.levelEventProvider() instanceof Sonic3kLevelEventManager manager)) {
            return null;
        }
        Sonic3kMGZEvents mgzEvents = manager.getMgzEvents();
        if (mgzEvents == null) {
            return null;
        }
        return mgzEvents.recreateCollapseSolidForRewind(ctx.spawn());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM sets the invisible status bit; collision only.
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }
}
