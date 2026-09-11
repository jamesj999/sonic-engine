package com.openggf.game;

import com.openggf.game.rules.GameRules;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the per-game {@code sidekickDespawnX} feature-flag values match the
 * disassembly references documented on {@link GameRules}.
 *
 * <p>Disassembly references:
 * <ul>
 *   <li>S3K: {@code sub_13ECA} writes {@code #$7F00, x_pos(a0)}
 *       (sonic3k.asm:26800-26807).</li>
 *   <li>S2:  {@code TailsCPU_RespawnTails} resets Tails to Sonic's position
 *       instead of consuming an off-screen marker, so the engine retains its
 *       historic {@code 0x4000} placeholder for parity with existing traces.</li>
 *   <li>S1:  no Tails CPU sidekick — the value is unreachable and kept
 *       symmetric with S2 for clarity.</li>
 * </ul>
 */
class TestSidekickDespawnXRule {

    @Test
    void sonic1UsesS2Placeholder() {
        assertEquals(0x4000, GameRules.SONIC_1.sidekickCpu().sidekickDespawnX(),
                "S1 has no Tails CPU sidekick; keep S2 placeholder for symmetry");
    }

    @Test
    void sonic2UsesEnginePlaceholder() {
        assertEquals(0x4000, GameRules.SONIC_2.sidekickCpu().sidekickDespawnX(),
                "S2 preserves the historic 0x4000 placeholder so existing traces are not disturbed");
    }

    @Test
    void sonic3kMatchesRomSub13ECA() {
        assertEquals(0x7F00, GameRules.SONIC_3K.sidekickCpu().sidekickDespawnX(),
                "S3K must match sub_13ECA's #$7F00 x_pos write (sonic3k.asm:26806)");
    }

    @Test
    void s3kConstantMirrorsRomValue() {
        // Sanity check that the central constant matches the rule.
        assertEquals(Sonic3kConstants.TAILS_CPU_DESPAWN_X,
                GameRules.SONIC_3K.sidekickCpu().sidekickDespawnX(),
                "S3K rule must mirror the central Sonic3kConstants entry");
        assertEquals(GameRules.SONIC_3K.sidekickCpu().sidekickDespawnX(),
                GameRules.SONIC_3K.sidekickCpu().sidekickDespawnX(),
                "S3K rule must mirror the SIDEKICK_DESPAWN_X_S3K named constant");
    }
}
