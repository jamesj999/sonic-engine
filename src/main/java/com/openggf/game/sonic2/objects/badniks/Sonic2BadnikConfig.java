package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.objects.PointsObjectInstance;
import com.openggf.level.objects.AnimalObjectInstance;
import com.openggf.level.objects.DestructionEffects.DestructionConfig;

/**
 * Shared destruction configuration for all Sonic 2 badniks.
 */
public final class Sonic2BadnikConfig {

    /** Standard S2 badnik destruction: explosion SFX, spawn animal, award points. */
    public static final DestructionConfig DESTRUCTION = new DestructionConfig(
            Sonic2Sfx.EXPLOSION.id,
            (spawn, svc) -> AnimalObjectInstance.deferredArtVariant(spawn, svc,
                    (pointsSpawn, pointsSvc, points) -> new PointsObjectInstance(pointsSpawn, pointsSvc, points)),
            true,   // useRespawnTracking for placements with the ROM respawn bit set
            null,   // S2 Obj28_InitRandom, not Obj27, allocates Obj29.
            null,
            false
    );

    private Sonic2BadnikConfig() {
        // Utility class
    }
}
