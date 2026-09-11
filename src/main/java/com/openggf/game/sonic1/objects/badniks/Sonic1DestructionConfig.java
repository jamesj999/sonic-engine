package com.openggf.game.sonic1.objects.badniks;

import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.objects.Sonic1ExplosionItemObjectInstance;
import com.openggf.level.objects.DestructionEffects.DestructionConfig;

/**
 * Shared S1 badnik destruction configuration.
 * <p>
 * S1 differs from S2 in three ways:
 * <ul>
 *   <li>SFX: {@code sfx_BreakItem} (0xC1) instead of S2's explosion SFX</li>
 *   <li>Badniks become {@code ExplosionItem}, which later spawns the animal</li>
 *   <li>Respawn tracking: uses {@code markRemembered} for respawn-tracked spawns</li>
 * </ul>
 */
public final class Sonic1DestructionConfig {

    /** Standard S1 badnik destruction config used by all S1 badniks. */
    public static final DestructionConfig S1_DESTRUCTION_CONFIG = new DestructionConfig(
            Sonic1Sfx.BREAK_ITEM.id,
            null,
            true,   // useRespawnTracking (S1 uses markRemembered)
            null,
            Sonic1ExplosionItemObjectInstance::new,
            false
    );

    private Sonic1DestructionConfig() {
        // utility class
    }
}
