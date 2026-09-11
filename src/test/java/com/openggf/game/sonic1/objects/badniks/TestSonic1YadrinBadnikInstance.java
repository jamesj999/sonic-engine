package com.openggf.game.sonic1.objects.badniks;

import com.openggf.game.GameStateManager;
import com.openggf.game.sonic1.objects.TestPlayableSprite;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers React_Yadrin's spike-region gating (s1disasm/_incObj/Sonic
 * ReactToItem.asm:532-561), triaging the reported "Yadrin's spikes can be
 * jumped on safely" symptom (S1 bug batch ledger row 8).
 * <p>
 * ROM: the spiked section is a real 24px-wide sub-hitbox at Yadrin's top,
 * offset 4px from its leading edge (mirrored 16px further when facing
 * right), that only applies on a SHALLOW vertical graze (Sonic's react-hitbox
 * bottom edge clips &lt;8px into Yadrin's top edge). It always hurts, even
 * while Sonic is attacking. Any other contact (deep overlap, or touching
 * outside the spike's X window) falls through to normal badnik rules
 * (React_Enemy): hurts if not attacking, destroys if attacking.
 * <p>
 * Yadrin spawns at (160,100) with ROM touch-hitbox radii from col_40x32
 * (width=20, height=16 -- s1disasm/_incObj/Sonic ReactToItem.asm:92) and
 * defaults to facing left (renderFlags=0), so the spiked region sits at
 * X in [156,180). {@link TestPlayableSprite} defaults to standYRadius=19 /
 * rollYRadius=14 (see {@code Sonic1YadrinBadnikInstance#isSpikeRegionHit}
 * for the exact penetration formula: {@code sonicBottom - yadrinTop},
 * where {@code sonicBottom = centreY + max(1, yRadius-3)} and
 * {@code yadrinTop = 100 - 16 = 84}).
 */
public class TestSonic1YadrinBadnikInstance {

    private static final TouchResponseResult ROM_ACCURATE_RESULT =
            new TouchResponseResult(0x0C, 20, 16, TouchCategory.SPECIAL);

    @Test
    public void collisionFlagsUseSpecialCategory() {
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 0, false, 0));

        assertEquals(0x40, yadrin.getCollisionFlags() & 0xC0);
    }

    @Test
    public void customReactYadrinPathIsPolledEveryOverlappingFrame() {
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 0, false, 0));

        assertTrue(yadrin.getTouchResponseProfile().continuousCallbacks(),
                "S1 ReactToItem re-evaluates Yadrin's spike-vs-enemy branch every overlapping frame");
    }

    @Test
    public void rollingTopHitHurtsWithoutDestroyingBadnik() {
        // Rolling (attacking) Sonic landing squarely on the spiked head region
        // with a shallow graze (penetration=4, well within the <8 window):
        // sonicBottom = 77 + max(1, 14-3) = 77+11 = 88; yadrinTop = 84;
        // penetration = 4. X-aligned with Yadrin (spike region [156,180)).
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 0, false, 0));
        YadrinTestPlayableSprite player = new YadrinTestPlayableSprite();
        player.setRolling(true);
        player.setCentreX((short) 160);
        player.setCentreY((short) 77);

        yadrin.onTouchResponse(player, ROM_ACCURATE_RESULT, 12);

        assertTrue(player.hurtOrDeathCalled, "Spiked-top graze must hurt even while Sonic is rolling");
        assertFalse(yadrin.isDestroyed(), "Spiked-top graze must never destroy the badnik");
    }

    @Test
    public void rollingHitOutsideSpikeXWindowDestroysBadnikNormally() {
        // Same shallow-graze Y penetration as above, but Sonic's react-hitbox
        // (X in [202,218)) no longer overlaps the spike region ([156,180)):
        // this must fall through to normal badnik rules, where an attacking
        // (rolling) Sonic destroys Yadrin instead of getting hurt.
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 0, false, 0));
        yadrin.setServices(destroyCapableServices());
        YadrinTestPlayableSprite player = new YadrinTestPlayableSprite();
        player.setRolling(true);
        player.setCentreX((short) 210);
        player.setCentreY((short) 77);

        yadrin.onTouchResponse(player, ROM_ACCURATE_RESULT, 12);

        assertFalse(player.hurtOrDeathCalled, "Contact outside the spike's X window must not force a hurt");
        assertTrue(yadrin.isDestroyed(), "An attacking Sonic outside the spike window should destroy the badnik normally");
    }

    @Test
    public void spikeRegionUpperXBoundaryIsInclusive() {
        // Facing-left spike region is [156,180] (currentX=160, offset 4, width
        // 24). Sonic's react-hitbox is X in [172,188) when centreX=180, so
        // sonicLeft(172) sits exactly ON spikeRight(180)... use centreX=188 so
        // sonicLeft == spikeRight == 180 exactly (the reviewer-identified edge:
        // ReactToItem.asm:552-554's `cmp.w d4,d0 / bhi .normalBadnik` only
        // excludes strictly-greater-than-16, so this exact edge still hits).
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 0, false, 0));
        yadrin.setServices(destroyCapableServices());
        YadrinTestPlayableSprite player = new YadrinTestPlayableSprite();
        player.setRolling(true);
        player.setCentreX((short) 188); // sonicLeft = 180 == spikeRight
        player.setCentreY((short) 77);

        yadrin.onTouchResponse(player, ROM_ACCURATE_RESULT, 12);

        assertTrue(player.hurtOrDeathCalled, "sonicLeft == spikeRight is an inclusive spike-region hit");
        assertFalse(yadrin.isDestroyed());
    }

    @Test
    public void spikeRegionLowerXBoundaryIsInclusive() {
        // Facing-left spike region is [156,180]. Sonic's react-hitbox with
        // centreX=148 gives sonicRight == 156 == spikeLeft exactly.
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 0, false, 0));
        yadrin.setServices(destroyCapableServices());
        YadrinTestPlayableSprite player = new YadrinTestPlayableSprite();
        player.setRolling(true);
        player.setCentreX((short) 148); // sonicRight = 156 == spikeLeft
        player.setCentreY((short) 77);

        yadrin.onTouchResponse(player, ROM_ACCURATE_RESULT, 12);

        assertTrue(player.hurtOrDeathCalled, "sonicRight == spikeLeft is an inclusive spike-region hit");
        assertFalse(yadrin.isDestroyed());
    }

    @Test
    public void mirroredSpikeRegionUpperXBoundaryIsInclusiveWhenFacingRight() {
        // renderFlags=1 -> xFlip -> facingLeft=false -> facing right, mirroring
        // the spike region 16px further left: [140,164] instead of [156,180].
        // This exercises SPIKE_REGION_FACING_RIGHT_MIRROR, which no prior test
        // covered (every other test uses renderFlags=0/facing left).
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 1, false, 0));
        yadrin.setServices(destroyCapableServices());
        YadrinTestPlayableSprite player = new YadrinTestPlayableSprite();
        player.setRolling(true);
        player.setCentreX((short) 172); // sonicLeft = 164 == mirrored spikeRight
        player.setCentreY((short) 77);

        yadrin.onTouchResponse(player, ROM_ACCURATE_RESULT, 12);

        assertTrue(player.hurtOrDeathCalled,
                "Mirrored (facing-right) spike region upper edge (sonicLeft == spikeRight) must be an inclusive hit");
        assertFalse(yadrin.isDestroyed());
    }

    @Test
    public void mirroredSpikeRegionLowerXBoundaryIsInclusiveWhenFacingRight() {
        // Mirrored spike region [140,164]: sonicRight == 140 == spikeLeft exactly.
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 1, false, 0));
        yadrin.setServices(destroyCapableServices());
        YadrinTestPlayableSprite player = new YadrinTestPlayableSprite();
        player.setRolling(true);
        player.setCentreX((short) 132); // sonicRight = 140 == mirrored spikeLeft
        player.setCentreY((short) 77);

        yadrin.onTouchResponse(player, ROM_ACCURATE_RESULT, 12);

        assertTrue(player.hurtOrDeathCalled,
                "Mirrored (facing-right) spike region lower edge (sonicRight == spikeLeft) must be an inclusive hit");
        assertFalse(yadrin.isDestroyed());
    }

    @Test
    public void nonAttackingDeepOverlapStillHurtsViaNormalEnemyRules() {
        // Deep vertical overlap (penetration=32, far outside the <8 graze
        // window) is not a spike-region touch at all -- but a non-attacking
        // Sonic still gets hurt via the ordinary React_Enemy path.
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 0, false, 0));
        YadrinTestPlayableSprite player = new YadrinTestPlayableSprite();
        player.setCentreX((short) 160);
        player.setCentreY((short) 100);

        yadrin.onTouchResponse(player, ROM_ACCURATE_RESULT, 12);

        assertTrue(player.hurtOrDeathCalled, "Non-attacking contact must still hurt via normal enemy rules");
        assertFalse(yadrin.isDestroyed());
    }

    @Test
    public void attackingDeepOverlapDestroysBadnikNormally() {
        // Deep vertical overlap while rolling (attacking): normal badnik
        // defeat rules apply (this is not the spiked-top special case), so
        // Yadrin is destroyed rather than hurting Sonic.
        Sonic1YadrinBadnikInstance yadrin = new Sonic1YadrinBadnikInstance(
                new ObjectSpawn(160, 100, 0x50, 0, 0, false, 0));
        yadrin.setServices(destroyCapableServices());
        YadrinTestPlayableSprite player = new YadrinTestPlayableSprite();
        player.setRolling(true);
        player.setCentreX((short) 160);
        player.setCentreY((short) 100);

        yadrin.onTouchResponse(player, ROM_ACCURATE_RESULT, 12);

        assertFalse(player.hurtOrDeathCalled);
        assertTrue(yadrin.isDestroyed(), "Deep overlap while attacking should destroy the badnik, not hurt Sonic");
    }

    /**
     * Minimal services stub for tests that drive Yadrin's attacking-badnik
     * destroy path: {@code AbstractBadnikInstance.destroyBadnik()} requires a
     * non-null {@code services()}, and {@code DestructionEffects.destroyBadnik}
     * calls {@code gameState().addScore(...)} whenever a non-null player is
     * passed. objectManager()/renderManager() stay null (the default
     * StubObjectServices), which safely short-circuits the explosion-spawn and
     * respawn-tracking branches in DestructionEffects.
     */
    private static com.openggf.level.objects.ObjectServices destroyCapableServices() {
        GameStateManager gameState = mock(GameStateManager.class);
        return new StubObjectServices() {
            @Override
            public GameStateManager gameState() {
                return gameState;
            }
        };
    }

    private static final class YadrinTestPlayableSprite extends TestPlayableSprite {
        private boolean hurtOrDeathCalled;

        @Override
        public boolean applyHurtOrDeath(int sourceX, boolean spikeHit, boolean hadRings) {
            hurtOrDeathCalled = true;
            return true;
        }
    }
}
