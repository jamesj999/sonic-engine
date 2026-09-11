package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * S3K hidden monitor (Object 0x80).
 *
 * <p>ROM: Obj_HiddenMonitor (sonic3k.asm) — invisible until the signpost
 * lands nearby, at which point it either reveals itself (in range) or
 * plays a sound and disappears (out of range).
 *
 * <p>Subtype encodes the monitor contents type.
 */
public class S3kHiddenMonitorInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(S3kHiddenMonitorInstance.class.getName());

    // ROM word_8379E = -$E, $1C, -$80, $C0 (docs/skdisasm/sonic3k.asm:176098).
    // Obj_HiddenMonitorMain applies these CUMULATIVELY to a running d0 -- it
    // does `add.w (a2)+,d0` twice per axis without reloading the monitor
    // position (docs/skdisasm/sonic3k.asm:176052-176069), so the second word
    // is the window *span*, not an independent offset from the monitor.
    // The real windows are therefore
    //   x: [monX - $E, monX - $E + $1C) = [monX - $E, monX + $E)
    //   y: [monY - $80, monY - $80 + $C0) = [monY - $80, monY + $40)
    // and the comparisons are unsigned word compares (blo / bhs).
    private static final int RANGE_X_LOW = -0x0E;
    private static final int RANGE_X_SPAN = 0x1C;
    private static final int RANGE_Y_LOW = -0x80;
    private static final int RANGE_Y_SPAN = 0xC0;

    private int monitorX;
    private int monitorY;
    private int monitorSubtype;
    private boolean resolved;

    public S3kHiddenMonitorInstance(ObjectSpawn spawn) {
        super(spawn, "HiddenMonitor");
        this.monitorX = spawn.x();
        this.monitorY = spawn.y();
        this.monitorSubtype = spawn.subtype();
    }

    @Override
    public S3kHiddenMonitorInstance recreateForRewind(RewindRecreateContext ctx) {
        return new S3kHiddenMonitorInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (resolved) {
            if (!isOnScreenX()) {
                setDestroyedByOffscreen();
            }
            return;
        }

        S3kSignpostInstance signpost = S3kSignpostInstance.activeSignpost(services().objectManager());
        if (signpost == null) {
            return;
        }

        if (!signpost.isLanded()) {
            return;
        }

        // Signpost has landed — resolve this hidden monitor
        resolved = true;

        if (romSignpostInRange(monitorX, monitorY,
                signpost.getWorldX(), signpost.getWorldY())) {
            // In range: reveal monitor, bounce signpost
            // ROM: loc_83760 — bclr #0,$38(a1) clears signpost landed flag,
            // then transforms into Obj_Monitor with y_vel = -$500
            LOG.fine("Hidden monitor at (" + monitorX + "," + monitorY
                    + ") IN RANGE of signpost — revealing");
            try {
                services().playSfx(Sonic3kSfx.BUBBLE_ATTACK.id);
            } catch (Exception e) {
                LOG.fine("Could not play bubble attack SFX: " + e.getMessage());
            }
            signpost.setLanded(false);

            // Spawn a visible monitor that pops upward and falls with gravity
            ObjectSpawn monitorSpawn = new ObjectSpawn(
                    monitorX, monitorY, 0x01, monitorSubtype, 0, false, 0);
            Sonic3kMonitorObjectInstance monitor = new Sonic3kMonitorObjectInstance(monitorSpawn);
            monitor.revealFromHidden();
            ObjectManager objectManager = services().objectManager();
            if (objectManager != null) {
                ObjectLifetimeOps.addReplacementAtTransferredSlot(objectManager, monitor, getSlotIndex());
            } else {
                spawnDynamicObject(monitor);
            }
            setDestroyed(true);
        } else {
            // Out of range: switch to the ROM Sprite_OnScreen_Test path.
            LOG.fine("Hidden monitor at (" + monitorX + "," + monitorY
                    + ") OUT OF RANGE of signpost — waiting for offscreen delete");
            try {
                services().playSfx(Sonic3kSfx.GROUND_SLIDE.id);
            } catch (Exception e) {
                LOG.fine("Could not play ground slide SFX: " + e.getMessage());
            }
        }
    }

    /**
     * ROM {@code Obj_HiddenMonitorMain} range test against {@code word_8379E}
     * (docs/skdisasm/sonic3k.asm:176052-176069, 176098). Each axis loads the
     * monitor coordinate into {@code d0} once and adds the two table words to
     * it in turn, so the window is {@code [coord + low, coord + low + span)},
     * tested with unsigned word compares.
     */
    static boolean romSignpostInRange(int monitorX, int monitorY, int signpostX, int signpostY) {
        return inRomWindow(monitorX, signpostX, RANGE_X_LOW, RANGE_X_SPAN)
                && inRomWindow(monitorY, signpostY, RANGE_Y_LOW, RANGE_Y_SPAN);
    }

    private static boolean inRomWindow(int base, int value, int low, int span) {
        int lowEdge = (base + low) & 0xFFFF;
        int highEdge = (lowEdge + span) & 0xFFFF;
        int probe = value & 0xFFFF;
        return probe >= lowEdge && probe < highEdge;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Hidden monitors are invisible until revealed.
        // No rendering needed.
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }
}
