package com.openggf.game.sonic2.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the batch-inner2 S2 DEZ Death Egg Robot bomb child no longer
 * has an explicit dynamic rewind codec and instead opts into graph-tested
 * {@link RewindRecreatable} generic recreate.
 *
 * <p>These are {@code static} nested children of the DEZ Death Egg Robot and WFZ
 * boss, keyed by its JVM binary name ({@code Outer$Inner}). Its
 * {@code recreateForRewind} hook relinks the live parent boss (recreated earlier
 * in the restore loop, so present in {@code getActiveObjects()}) and constructs
 * the package-private nested type; the in-flight differentiator scalars are
 * already non-final, so the generic field capturer reapplies them after recreate
 * (no un-finaling required):
 * <ul>
 *   <li>{@code Sonic2DeathEggRobotInstance$BombChild} — fired bomb HURT hazard flying its
 *       own arc from an attack routine; not re-emitted by the body.</li>
 * </ul>
 *
 * <p><b>Intentionally absent:</b> {@code ArticulatedChild}, {@code HeadChild}, and
 * {@code JetChild} are construction-spawned (inside {@code initializeBossState() →
 * spawnChildren()}). Registering a codec for them would double their count on restore
 * (boss reconstruction re-spawns them, then the codec adds another copy). They are
 * re-established by boss reconstruction and must NOT have codecs.
 * See {@code TestBossChildNoDoubleSpawnParity} and {@code docs/status/known-discrepancies.md}.
 * The WFZ floating-platform, laser-wall, and platform-hurt children now restore
 * through graph-tested {@code RewindRecreatable} generic recreate.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip coverage is enforced by the
 * rewind coverage guard ({@code TestRewindCoverageGuard}).
 */
class TestRewindFixS2InnerBatch2Codecs {

    @Test
    void deathEggRobotBombChildUsesRewindRecreatableWithoutExplicitCodec() throws Exception {
        // ArticulatedChild, HeadChild, JetChild are intentionally NOT checked:
        // they are construction-spawned and must NOT have codecs.
        String bombChildClassName =
                "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance"
                        + "$BombChild";

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(bombChildClassName),
                "DEZ BombChild must restore through RewindRecreatable generic recreate, "
                        + "not an explicit dynamic codec");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(bombChildClassName)),
                "DEZ BombChild must opt into the generic RewindRecreatable path");
    }
}
