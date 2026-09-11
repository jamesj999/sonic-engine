package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x13 - Lava Ball Maker (MZ, SLZ).
 * <p>
 * Invisible spawner that periodically creates Lava Ball objects (0x14) at its position.
 * The spawner has no visible sprite; it only manages a countdown timer and creates
 * child lava ball objects when the timer expires and the spawner is on screen.
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Upper nybble (bits 4-7): Rate index into LavaM_Rates table (0-5),
 *       selecting spawn intervals of 30, 60, 90, 120, 150, or 180 frames</li>
 *   <li>Lower nybble (bits 0-3): Passed to spawned Lava Ball as its subtype,
 *       selecting ball behavior (vertical, ceiling, floor, horizontal)</li>
 * </ul>
 * <p>
 * <b>Subtypes from level data:</b>
 * <table border="1">
 *   <tr><th>Subtype</th><th>Rate</th><th>Ball Type</th><th>Description</th></tr>
 *   <tr><td>0x17</td><td>30 (rate 1)</td><td>7</td><td>Horizontal rightward ball</td></tr>
 *   <tr><td>0x30</td><td>90 (rate 3)</td><td>0</td><td>Vertical ball, medium speed</td></tr>
 *   <tr><td>0x36</td><td>90 (rate 3)</td><td>6</td><td>Horizontal leftward ball</td></tr>
 *   <tr><td>0x37</td><td>90 (rate 3)</td><td>7</td><td>Horizontal rightward ball</td></tr>
 *   <tr><td>0x41</td><td>120 (rate 4)</td><td>1</td><td>Vertical ball, fast speed</td></tr>
 *   <tr><td>0x42</td><td>120 (rate 4)</td><td>2</td><td>Vertical ball, fastest speed</td></tr>
 * </table>
 * <p>
 * Reference: docs/s1disasm/_incObj/13 Lava Ball Maker.asm
 */
public class Sonic1LavaBallMakerObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /**
     * LavaM_Rates: Spawn rate table indexed by upper nybble of subtype.
     * From disassembly: dc.b 30, 60, 90, 120, 150, 180
     */
    private static final int[] SPAWN_RATES = {30, 60, 90, 120, 150, 180};

    /** Debug rendering color for lava spawner (dark red). */
    private static final DebugColor DEBUG_COLOR = new DebugColor(200, 50, 0);

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Spawn delay in frames, from LavaM_Rates. Stored in obDelayAni. */
    private int spawnDelay;

    /** Countdown timer. Stored in obTimeFrame. */
    private int timer;

    /** Lower nybble of original subtype, passed to spawned lava balls as their subtype. */
    private int ballSubtype;

    /** Rate index for debug display. */
    private int rateIndex;

    public Sonic1LavaBallMakerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LavaBallMaker");

        // ROM: LavaM_Main
        // move.b obSubtype(a0),d0
        // lsr.w #4,d0
        // andi.w #$F,d0
        // move.b LavaM_Rates(pc,d0.w),obDelayAni(a0)
        int subtype = spawn.subtype();
        this.rateIndex = (subtype >> 4) & 0x0F;
        int rateIdx = Math.min(rateIndex, SPAWN_RATES.length - 1);
        this.spawnDelay = SPAWN_RATES[rateIdx];

        // move.b obDelayAni(a0),obTimeFrame(a0) ; set time delay for lava balls
        this.timer = spawnDelay;

        // andi.b #$F,obSubtype(a0)
        this.ballSubtype = subtype & 0x0F;
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ObjPosLoad instantiates S1 placement objects after the exec loop, so the
        // constructor already mirrors LavaM_Main. The first update must therefore run
        // LavaM_MakeLava immediately or the countdown drifts one frame late.
        // ROM: LavaM_MakeLava (Routine 2)
        // subq.b #1,obTimeFrame(a0) ; subtract 1 from time delay
        timer--;
        // bne.s LavaM_Wait ; if time still remains, branch
        if (timer > 0) {
            return;
        }
        // move.b obDelayAni(a0),obTimeFrame(a0) ; reset time delay
        timer = spawnDelay;

        // bsr.w ChkObjectVisible
        // bne.s LavaM_Wait
        if (!isChkObjectVisible()) {
            return;
        }

        // bsr.w FindFreeObj
        // bne.s LavaM_Wait
        if (services().objectManager() == null) {
            return;
        }

        // _move.b #id_LavaBall,obID(a1) ; load lava ball object
        // move.w obX(a0),obX(a1)
        // move.w obY(a0),obY(a1)
        // move.b obSubtype(a0),obSubtype(a1)
        ObjectSpawn ballSpawn = new ObjectSpawn(
                spawn.x(), spawn.y(),
                0x14,  // id_LavaBall
                ballSubtype,
                0, false, 0);
        spawnFreeChild(() -> new Sonic1LavaBallObjectInstance(ballSpawn));
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible spawner - no visible sprite in normal gameplay.
    }

    @Override
    public int getPriorityBucket() {
        return 0;
    }

    // ========================================================================
    // Debug Rendering
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawCross(spawn.x(), spawn.y(), 6, 0.8f, 0.2f, 0.0f);
        ctx.drawWorldLabel(spawn.x(), spawn.y(), -1,
                String.format("LavaMaker rate=%d ball=%d", spawnDelay, ballSubtype),
                DEBUG_COLOR);
    }
}
