package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every AIZ2 battleship / boss-endgame object that must survive a
 * rewind keyframe restore has a Phase-2 {@link RewindRecreatable} generic path.
 *
 * <p>Held rewind restores the nearest keyframe and re-simulates forward each
 * displayed frame, so any object dropped on restore is re-emitted from scratch
 * and visibly plays forward. To make the whole scene reverse cleanly, every AIZ2
 * battleship/boss child is now captured and recreated — there are no longer any
 * intentionally-dropped AIZ2 transients.
 *
 * <p>This is a pure recreate-path test: it reads class metadata without a ROM,
 * OpenGL, or an active gameplay session.
 */
class TestAiz2ObjectRewindCodecs {

    private static boolean hasDynamicRecreatePath(Class<?> type) {
        return RewindRecreatable.class.isAssignableFrom(type);
    }

    private static void assertGenericBacked(Class<?> type) {
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                type.getSimpleName() + " codec should be deleted via Phase-2 generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                type.getSimpleName() + " must implement RewindRecreatable after codec deletion");
        assertTrue(hasDynamicRecreatePath(type),
                "missing dynamic recreate path for " + type.getSimpleName());
    }

    @Test
    void hasDynamicRecreatePathsForAiz2RecreatableObjects() {
        // Tier 1: self-contained objects may use codecs or Phase-2 generic recreate.
        assertTrue(hasDynamicRecreatePath(AizBgTreeSpawnerInstance.class),
                "missing dynamic recreate path for AizBgTreeSpawnerInstance");
        assertTrue(hasDynamicRecreatePath(AizBossSmallInstance.class),
                "missing dynamic recreate path for AizBossSmallInstance");
        assertTrue(hasDynamicRecreatePath(AizMinibossNapalmProjectile.class),
                "missing dynamic recreate path for AizMinibossNapalmProjectile");

        // Tier 2: non-final differentiator reapplied after recreate.
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(AizBattleshipInstance.class.getName()),
                "AizBattleshipInstance codec should be deleted via Phase-2 generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(AizBattleshipInstance.class),
                "AizBattleshipInstance must implement RewindRecreatable after codec deletion");
        assertTrue(hasDynamicRecreatePath(AizBattleshipInstance.class),
                "missing dynamic recreate path for AizBattleshipInstance");
        assertGenericBacked(AizBgTreeInstance.class);
        assertGenericBacked(Aiz2BossEndSequenceController.class);

        // Tier 3: boss-relinked children.
        assertGenericBacked(AizMinibossBodyChild.class);
        assertGenericBacked(AizMinibossArmChild.class);
        assertGenericBacked(AizMinibossFlameBarrelChild.class);
        assertGenericBacked(AizEndBossShipChild.class);
        assertGenericBacked(AizEndBossFlameColumnChild.class);
        assertGenericBacked(AizEndBossWaterfallChild.class);
        assertGenericBacked(AizEndBossArmChild.class);
    }

    @Test
    void hasRecreatePathsForFormerlyDroppedTransientChildren() {
        // Previously Tier-4 (dropped). Now captured + recreated so held rewind
        // reverses them cleanly instead of re-emitting them forward.
        List<Class<?>> genericBacked = List.of(
                AizShipBombInstance.class,
                AizMinibossBarrelShotChild.class,
                AizMinibossBarrelShotFlareChild.class,
                AizMinibossFlameChild.class,
                AizMinibossNapalmExplosionChild.class,
                AizEndBossPropellerChild.class,
                AizEndBossFlameChild.class,
                AizEndBossBombChild.class,
                AizEndBossSmokeChild.class);
        for (Class<?> type : genericBacked) {
            assertGenericBacked(type);
        }
    }

    @Test
    void selfContainedTransientChildrenUseGenericRecreateWithoutHandwrittenCodecs() {
        List<Class<?>> genericBacked = List.of(
                AizBombExplosionInstance.class,
                AizEndBossDebrisChild.class,
                AizMinibossImpactFlameChild.class,
                AizMinibossDebrisChild.class);
        for (Class<?> type : genericBacked) {
            assertGenericBacked(type);
        }
    }
}
