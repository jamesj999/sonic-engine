package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameRng;

import java.util.ArrayList;
import java.util.List;

/**
 * S3K boss explosion controller (ROM: Obj_BossExplosionSpecial → Obj_CreateBossExplosion).
 * Plain Java object (not an ObjectInstance) — ticked directly by the owning boss.
 *
 * ROM flow (sonic3k.asm Obj_BossExpControl / Obj_NormalExpControl):
 * 1. Obj_CreateBossExplosion initializes wait timer
 * 2. Obj_NormalExpControl: decrement $39 timer, spawn explosion, set $2E = 3-1
 * 3. Obj_Wait: 3 frames between subsequent explosions
 * 4. Repeat until $39 decrements to zero, then delete before spawning.
 *
 * CreateBossExp02: timer=$28, xRange=$80, yRange=$80, routine=2
 * Result: 39 explosions ($27→$01 inclusive), each spaced 3 frames apart.
 * Total duration: 3 + 39*3 plus the zero-timer delete wait.
 *
 * SFX (sfx_Explode) is played by the controller (sub_52850), not by each child.
 */
public class S3kBossExplosionController {
    private static final int[][] SUBTYPE_PARAMS = {
            {0x20, 0x20, 0x20, 0x00},
            {0x28, 0x80, 0x80, 0x18},
            {0x80, 0x20, 0x20, 0x08},
            {0x04, 0x10, 0x10, 0x00},
            {0x08, 0x20, 0x20, 0x10},
            {0x20, 0x20, 0x20, 0x00},
            {0x40, 0x80, 0x20, 0x00},
            {0x80, 0x40, 0x40, 0x08},
            {0x20, 0x20, 0x20, 0x18},
            {0x80, 0x20, 0x20, 0x20},
            {0x08, 0x80, 0x20, 0x10},
            {0x80, 0x80, 0x80, 0x08},
            {0x80, 0x80, 0x80, 0x28},
            {0x80, 0x40, 0x40, 0x28},
            {0x80, 0x80, 0x40, 0x08},
            {0x80, 0x10, 0x10, 0x08},
            {0x80, 0x20, 0x20, 0x30},
    };
    // ROM: move.w #3-1,$2E(a0) — Obj_Wait counts $2E down, fires at -1 = 3 frame cycle
    private static final int SPAWN_INTERVAL = 3;

    private final int centreX;
    private final int centreY;
    private final int xRange;
    private final int yRange;
    private final GameRng rng;
    private int timer;
    private int intervalCounter;
    private final List<PendingExplosion> pendingExplosions = new ArrayList<>();

    public record PendingExplosion(int x, int y, boolean playSfx) {}

    public S3kBossExplosionController(int centreX, int centreY, int subtype) {
        this(centreX, centreY, subtype, new GameRng(GameRng.Flavour.S3K));
    }

    public S3kBossExplosionController(int centreX, int centreY, int subtype, GameRng rng) {
        this.centreX = centreX;
        this.centreY = centreY;
        this.rng = rng;
        int paramIndex = (subtype & 0xFF) >> 1;
        if (paramIndex >= SUBTYPE_PARAMS.length) {
            throw new IllegalArgumentException("Unsupported boss explosion subtype: " + subtype);
        }
        int[] params = SUBTYPE_PARAMS[paramIndex];
        this.timer = params[0];
        this.xRange = params[1];
        this.yRange = params[2];
        this.intervalCounter = SPAWN_INTERVAL - 1;
    }

    public void tick() {
        if (timer < 0) return;
        intervalCounter--;
        if (intervalCounter >= 0) {
            return;
        }
        // ROM timed controls decrement $39 and branch to delete when it becomes zero
        // before creating a child (sonic3k.asm:176775-176792).
        timer--;
        if (timer <= 0) {
            timer = -1;
            return;
        }
        // ROM: sub_52850 plays sfx_Explode, spawns child, applies random offset.
        spawnExplosionChild();
        intervalCounter = SPAWN_INTERVAL - 1; // ROM: move.w #3-1,$2E(a0)
    }

    public boolean isFinished() {
        return timer < 0;
    }

    /**
     * Runs the callback reached by Obj_CreateBossExplosion's creation-time
     * tail jump through Obj_Wait while its zeroed $2E is already expired.
     */
    public void dispatchCreation() {
        if (timer <= 0 || isFinished()) {
            return;
        }
        timer--;
        if (timer <= 0) {
            timer = -1;
            return;
        }
        spawnExplosionChild();
        intervalCounter = SPAWN_INTERVAL - 1;
    }

    private void spawnExplosionChild() {
        // ROM: sub_52850 random offset calculation (sonic3k.asm:176746-176751).
        int random = rng.nextRaw();
        int xMask = (xRange * 2) - 1;
        int yMask = (yRange * 2) - 1;
        int xOffset = (random & xMask) - xRange;
        int yOffset = ((random >> 8) & yMask) - yRange;
        pendingExplosions.add(new PendingExplosion(centreX + xOffset, centreY + yOffset, true));
    }

    public List<PendingExplosion> drainPendingExplosions() {
        List<PendingExplosion> result = List.copyOf(pendingExplosions);
        pendingExplosions.clear();
        return result;
    }
}
