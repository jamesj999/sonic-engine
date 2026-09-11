package com.openggf.game.rules;

/**
 * How each game's player boundary code treats the edges of the level: the side
 * clamps run by {@code Sonic_LevelBound} / {@code Player_LevelBound}, and the
 * two rows below them — the bottom kill plane and, once the corpse is falling,
 * the row that hands off to the restart countdown.
 *
 * @param rightStrict
 *        S3K's {@code blo} right-edge test with no normal-play {@code +$40}
 *        extension (docs/skdisasm/sonic3k.asm:23183-23186), against S1/S2's
 *        {@code bls} plus the extension.
 * @param usesCentreY
 *        the bottom test compares the ROM {@code y_pos} word, i.e. centre-Y
 *        (docs/s1disasm/_incObj/01 Sonic.asm:1094; docs/s2disasm/s2.asm:36950;
 *        docs/skdisasm/sonic3k.asm:23195).
 * @param lockUsesScreenLockFlag
 *        the right-edge extension is removed by S1's persistent
 *        {@code f_lockscreen} (docs/s1disasm/_incObj/01 Sonic.asm:1071-1073)
 *        rather than S2's boss-alive {@code Current_Boss_ID}
 *        (docs/s2disasm/s2.asm:37247-37250).
 * @param deathFallBottomReferenceIsCameraBottomBoundary
 *        the death-restart row is measured from the camera's bottom boundary —
 *        S1 {@code v_limitbtm2} (docs/s1disasm/_incObj/01 Sonic.asm:2004) and S2
 *        {@code Camera_Max_Y_pos} (docs/s2disasm/s2.asm:38277) — rather than
 *        S3K's {@code Camera_Y_pos} (docs/skdisasm/sonic3k.asm:24541,24581).
 * @param deathFallRestartHandoffCancelsGravity
 *        the crossing frame writes {@code y_vel = -gravity} so the fall that
 *        follows it nets to no movement. S1 alone does this
 *        (docs/s1disasm/_incObj/01 Sonic.asm:2010); S2 and S3K leave the
 *        velocity alone and the corpse keeps falling that frame.
 * @param hurtStopBottomKillUnsigned
 *        {@code Sonic_HurtStop}'s own bottom-boundary kill test compares the two
 *        words with an UNSIGNED {@code blo} rather than a signed {@code blt}.
 *        This is S1's shipped ({@code FixBugs = 0}) branch
 *        (docs/s1disasm/_incObj/01 Sonic.asm:1935-1941): a player who has left
 *        the TOP of the level has a {@code y_pos} that wrapped to {@code $Fxxx},
 *        which the unsigned compare reads as a huge value, so a hurt Sonic flung
 *        off the top of the screen dies. Assembling with {@code FixBugs = 1}
 *        swaps in {@code blt}, which treats {@code $Fxxx} as negative and kills
 *        only at the bottom. S2 and S3K ship the signed form already
 *        (docs/s2disasm/s2.asm:38213-38214; docs/skdisasm/sonic3k.asm:24477-24481),
 *        so this is a genuine per-game divergence, not an S1 carve-out.
 *        <p>The companion divergence — which boundary word the row is measured
 *        from ({@code v_limitbtm2}/target for S1, the live
 *        {@code Camera_Max_Y_pos} for S2 and S3K) — is deliberately NOT modelled
 *        here. Both hurt-stop and {@code Sonic_LevelBound} kill planes currently
 *        run the {@code FixBugs = 1} {@code max(live, target)} expression, which
 *        is held in place because removing it regresses
 *        TestS2SczLevelSelectTraceReplay; see the note on the {@code maxY}
 *        assignment in {@code PlayableSpriteMovement.doLevelBoundary}. Under that
 *        shared mask the per-game word choice makes no observable difference, so
 *        adding a rule for it would be untestable plumbing. When the mask is
 *        lifted, both sites move together and the word choice becomes a real
 *        per-game rule again.
 */
public record PlayerLevelBoundaryRules(
        boolean rightStrict,
        boolean usesCentreY,
        boolean lockUsesScreenLockFlag,
        boolean usesPreEasedMaxXDuringBossLock,
        boolean deathFallBottomReferenceIsCameraBottomBoundary,
        boolean deathFallRestartHandoffCancelsGravity,
        boolean hurtStopBottomKillUnsigned) {
}
