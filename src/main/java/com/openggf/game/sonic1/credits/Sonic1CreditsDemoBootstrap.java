package com.openggf.game.sonic1.credits;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.level.WaterSystem;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Deterministic per-credits-demo bootstrap. Establishes the engine-side
 * ROM-only state that {@code EndingDemoLoad} (sonic.asm:3827) sets up before
 * each Sonic 1 credits demo runs.
 *
 * <p>Currently this only covers the LZ Act 3 lamppost variables that the ROM
 * copies from {@code EndDemo_LampVar} (sonic.asm:3879) into the lamppost
 * buffer when {@code v_creditsnum==4}. The per-credit zone/act, start
 * positions, and demo timers themselves are owned by
 * {@link Sonic1CreditsDemoData}; this class is the minimal seam for the
 * mid-level state ({@code v_rings}, camera, bottom boundary, water height)
 * that those constants alone don't apply.
 *
 * <p><b>Trace-replay invariant.</b> Per CLAUDE.md "Trace Replay Tests" the
 * comparison-only invariant requires that engine state be derived from ROM
 * defaults, never hydrated from {@code TraceEvent.StateSnapshot} events. All
 * values written here come from the {@code EndDemo_LampVar} table in the
 * disassembly via {@link Sonic1CreditsDemoData}; no value here is sourced
 * from a recorded trace. Any per-credit starting pose (animation id,
 * direction) is intentionally left to the engine's normal post-spawn init
 * and the first {@code Sonic_Animate} pass — if those diverge from the ROM
 * for an idle-on-spawn credit demo, the bug is in the spawn/animate path,
 * not something this bootstrap should paper over with trace-derived
 * overrides.
 */
public final class Sonic1CreditsDemoBootstrap {

    private Sonic1CreditsDemoBootstrap() {}

    /**
     * Applies the LZ-specific lamppost state for credit demo 3 (LZ Act 3).
     * Mirrors {@code EndDemo_LampVar} (sonic.asm:3879) which the ROM copies
     * into the lamppost buffer (and from there into the active level state)
     * before the LZ demo runs: ring count, camera position, bottom boundary,
     * and water height/routine.
     *
     * <p>Camera position and ring count are taken straight from
     * {@link Sonic1CreditsDemoData#LZ_LAMP_CAMERA_X} /
     * {@code LZ_LAMP_CAMERA_Y} / {@code LZ_LAMP_RINGS} — never from the
     * recorded trace.
     */
    public static void applyLzLampostState(AbstractPlayableSprite player,
                                           Camera camera) {
        if (player == null || camera == null) {
            return;
        }
        // Lamp_LoadInfo (s1disasm/_incObj/79 Lamppost.asm) writes
        // v_lamp_rings into v_rings then immediately clears v_rings to 0
        // via clr.w (v_rings).w on the very next line. The
        // EndDemo_LampVar dc.w 13 entry is therefore loaded then thrown
        // away, and ROM frame 0 always shows rings=0 for the LZ credits
        // demo. Match that observed ROM behaviour rather than the
        // misleading "13" constant. (LZ_LAMP_RINGS is retained in
        // Sonic1CreditsDemoData for documentation parity with the ROM
        // table layout.)
        player.setRingCount(0);
        camera.setX((short) Sonic1CreditsDemoData.LZ_LAMP_CAMERA_X);
        camera.setY((short) Sonic1CreditsDemoData.LZ_LAMP_CAMERA_Y);
        camera.setMaxY((short) Sonic1CreditsDemoData.LZ_LAMP_BOTTOM_BND);

        WaterSystem waterSystem = GameServices.water();
        int featureZone = GameServices.level().getFeatureZoneId();
        int featureAct = GameServices.level().getFeatureActId();
        waterSystem.setWaterLevelDirect(featureZone, featureAct,
                Sonic1CreditsDemoData.LZ_LAMP_WATER_HEIGHT);
        waterSystem.setWaterLevelTarget(featureZone, featureAct,
                Sonic1CreditsDemoData.LZ_LAMP_WATER_HEIGHT);

        ZoneFeatureProvider featureProvider = GameServices.level().getZoneFeatureProvider();
        if (featureProvider != null) {
            featureProvider.setWaterRoutine(Sonic1CreditsDemoData.LZ_LAMP_WATER_ROUTINE);
        }
        // NOTE: no v_vblank_count value is seeded here. The ROM's
        // v_vblank_count (s1disasm/_Variables.asm:350) is incremented once per
        // V-blank at VBlank_Exit (s1disasm/sonic.asm:685) and is written
        // nowhere else in the entire ROM -- nothing resets it on level load,
        // and EndDemo_LampVar (sonic.asm:3879) carries no vblank field. Its
        // value entering a credits demo is therefore a free-running count
        // since console reset, not level-derived state, and cannot be
        // reconstructed from the lamppost table. Seeding a measured phase here
        // is a fitted model; the counter is established once at frame 0 by the
        // replay entry path, exactly as every other trace-replay entry does.

        // Sync player's underwater flag with the water level we just set.
        // Without this, the first frame runs with inWater=false and uses
        // normal (non-underwater) acceleration, causing physics divergence.
        player.updateWaterState(Sonic1CreditsDemoData.LZ_LAMP_WATER_HEIGHT);
    }
}
