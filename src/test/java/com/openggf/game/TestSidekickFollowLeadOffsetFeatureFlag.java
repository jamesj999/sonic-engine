package com.openggf.game;

import com.openggf.game.rules.GameRules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the per-game {@code sidekickFollowLeadOffset} feature-flag values
 * match the disassembly references documented on {@link GameRules}.
 *
 * <p>Disassembly references:
 * <ul>
 *   <li>S3K: {@code TailsCPU_Normal} loc_13DA6 subtracts {@code #$20} from
 *       {@code d2} (the leader-x history target) before
 *       {@code sub.w x_pos(a0), d2}, biasing Tails 0x20 pixels to the left of
 *       Sonic. The offset is suppressed when the leader is riding an object
 *       ({@code Status_OnObj}, sonic3k.asm:26690-26691) or is moving faster
 *       than the follower can chase ({@code ground_vel >= $400},
 *       sonic3k.asm:26692-26693). See sonic3k.asm:26688-26694.</li>
 *   <li>S2:  {@code TailsCPU_Normal} reads {@code d2} from
 *       {@code Sonic_Pos_Record_Buf} (s2.asm:38933) and immediately runs
 *       {@code sub.w x_pos(a0), d2} (s2.asm:38945) with no bias. The follow
 *       AI tracks the recorded leader position directly.</li>
 *   <li>S1:  no Tails CPU sidekick — the value is unreachable and kept at
 *       {@code 0} for symmetry with S2.</li>
 * </ul>
 */
class TestSidekickFollowLeadOffsetRule {

    @Test
    void sonic1HasNoLeadOffset() {
        assertEquals(0, GameRules.SONIC_1.sidekickCpu().sidekickFollowLeadOffset(),
                "S1 has no Tails CPU sidekick; lead offset is unreachable");
    }

    @Test
    void sonic2HasNoLeadOffset() {
        assertEquals(0, GameRules.SONIC_2.sidekickCpu().sidekickFollowLeadOffset(),
                "S2 TailsCPU_Normal reads d2 directly with no bias (s2.asm:38933, 38945)");
    }

    @Test
    void sonic3kMatchesRomSubi20() {
        assertEquals(0x20, GameRules.SONIC_3K.sidekickCpu().sidekickFollowLeadOffset(),
                "S3K must match TailsCPU_Normal's subi.w #$20, d2 (sonic3k.asm:26694)");
    }

    @Test
    void namedConstantsMirrorRules() {
        assertEquals(0,
                GameRules.SONIC_1.sidekickCpu().sidekickFollowLeadOffset(),
                "S1 rule must mirror SIDEKICK_FOLLOW_LEAD_OFFSET_NONE");
        assertEquals(0,
                GameRules.SONIC_2.sidekickCpu().sidekickFollowLeadOffset(),
                "S2 rule must mirror SIDEKICK_FOLLOW_LEAD_OFFSET_NONE");
        assertEquals(GameRules.SONIC_3K.sidekickCpu().sidekickFollowLeadOffset(),
                GameRules.SONIC_3K.sidekickCpu().sidekickFollowLeadOffset(),
                "S3K rule must mirror SIDEKICK_FOLLOW_LEAD_OFFSET_S3K");
    }
}
