package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameRng;
import com.openggf.game.PlayableEntity;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TestBubblerObjectInstance {

    @Test
    void makerBeginsFirstProductionDispatchBeforeRenderVisibilityRefresh() {
        WaterSystem waterSystem = new WaterSystem() {
            @Override
            public boolean hasWater(int zoneId, int actId) {
                return true;
            }

            @Override
            public int getWaterLevelY(int zoneId, int actId) {
                return 0x0040;
            }
        };
        StubObjectServices services = new StubObjectServices() {
            @Override
            public WaterSystem waterSystem() {
                return waterSystem;
            }
        };
        long seed = 0x14A7ABBBL;
        services.rng().setSeed(seed);
        BubblerObjectInstance maker =
                new BubblerObjectInstance(new ObjectSpawn(0x0190, 0x0080, 0x54, 0x80, 0, false, 0));
        maker.setServices(services);

        maker.update(0, null);

        assertNotEquals(seed, services.rng().getSeed(),
                "SetUp_ObjAttributes render_flags=$84 makes the first maker dispatch production-eligible");
    }

    @Test
    void childBubbleInitializesWobbleAngleFromSharedRomRng() {
        StubObjectServices services = new StubObjectServices();
        long seed = 0x14A7ABBBL;
        services.rng().setSeed(seed);
        GameRng expected = new GameRng(GameRng.Flavour.S3K, seed);

        BubblerObjectInstance bubble =
                new BubblerObjectInstance(new ObjectSpawn(0x1200, 0x0400, 0x54, 0, 0, false, 0));
        bubble.setServices(services);

        bubble.update(0, null);

        assertEquals(expected.nextByte(), bubble.getWobbleAngleForTest() & 0xFF,
                "Obj_Bubbler initializes angle(a0) from Random_Number, sharing the global S3K RNG stream");
        assertEquals(expected.getSeed(), services.rng().getSeed(),
                "Bubbler must advance the same RNG stream later CNZ balloons use");
    }

    @Test
    void queryOnlySidekickCanCollectLargeBubbleWhenRawSidekickListIsEmpty() {
        TrackingPlayer main = submergedPlayer("sonic", 0x300, 0x300);
        TrackingPlayer sidekick = submergedPlayer("tails", 0x100, 0x100);
        BubblerObjectInstance bubble = largeBubbleAt(0x100, 0x100);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of(sidekick));
        bubble.setServices(services);

        updateUntilBubbleCollected(bubble, main, sidekick);

        assertEquals(0, main.replenishAirCalls);
        assertEquals(1, sidekick.replenishAirCalls,
                "Bubbler participation should come from ObjectPlayerQuery, not the raw sidekick list");
        assertEquals(1, services.sfxCalls,
                "The bubble should play its pickup SFX once when the query-only sidekick consumes it");
    }

    @Test
    void firstEligiblePlayerConsumesBubbleAndLaterActorsCannotAlsoConsumeIt() {
        TrackingPlayer main = submergedPlayer("sonic", 0x100, 0x100);
        TrackingPlayer sidekick = submergedPlayer("tails", 0x100, 0x100);
        BubblerObjectInstance bubble = largeBubbleAt(0x100, 0x100);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(main, List.of(sidekick));
        bubble.setServices(services);

        updateUntilBubbleCollected(bubble, main, main, sidekick);

        assertEquals(1, main.replenishAirCalls,
                "The main/update player keeps first pickup priority when multiple actors overlap one bubble");
        assertEquals(0, sidekick.replenishAirCalls,
                "Once a bubble is consumed, later query participants must not consume it in the same update");
        assertEquals(1, services.sfxCalls);
    }

    @Test
    void updatePlayerStillConsumesBeforeQueriedSidekickWhenQueryMainIsUnavailable() {
        TrackingPlayer updatePlayer = submergedPlayer("sonic", 0x100, 0x100);
        TrackingPlayer sidekick = submergedPlayer("tails", 0x100, 0x100);
        BubblerObjectInstance bubble = largeBubbleAt(0x100, 0x100);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(null, List.of(sidekick));
        bubble.setServices(services);

        updateUntilBubbleCollected(bubble, updatePlayer, updatePlayer, sidekick);

        assertEquals(1, updatePlayer.replenishAirCalls,
                "The update(...) player is the observable main participant when ObjectPlayerQuery has no main");
        assertEquals(0, sidekick.replenishAirCalls);
        assertEquals(1, services.sfxCalls);
    }

    @Test
    void largeBubblePreservesNonSonicRollingStatusLikeRetailObjectBranch() {
        TrackingPlayer tails = submergedPlayer("tails", 0x100, 0x100);
        tails.setRolling(true);
        BubblerObjectInstance bubble = largeBubbleAt(0x100, 0x100);
        QueryOnlyPlayerServices services = new QueryOnlyPlayerServices(null, List.of(tails));
        bubble.setServices(services);

        updateUntilBubbleCollected(bubble, tails, tails);

        assertEquals(1, tails.replenishAirCalls);
        assertEquals(true, tails.getRolling(),
                "Obj_Bubbler's non-Obj_Sonic branch restores radii without clearing Status_Roll");
        assertEquals(tails.getStandXRadius(), tails.getXRadius());
        assertEquals(tails.getStandYRadius(), tails.getYRadius());
    }

    private static BubblerObjectInstance largeBubbleAt(int x, int y) {
        return new BubblerObjectInstance(new ObjectSpawn(x, y, 0x54, 2, 0, false, 0));
    }

    private static TrackingPlayer submergedPlayer(String code, int x, int y) {
        TrackingPlayer player = new TrackingPlayer(code, x, y);
        player.setInWater(true);
        return player;
    }

    private static void updateUntilBubbleCollected(BubblerObjectInstance bubble,
                                                   PlayableEntity updatePlayer,
                                                   TrackingPlayer... overlappingPlayers) {
        for (int frame = 0; frame < 90 && totalReplenishAirCalls(overlappingPlayers) == 0; frame++) {
            for (TrackingPlayer player : overlappingPlayers) {
                player.setCentreX((short) bubble.getX());
                player.setCentreY((short) (bubble.getY() + 1));
            }
            bubble.update(frame, updatePlayer);
        }
    }

    private static int totalReplenishAirCalls(TrackingPlayer... players) {
        int total = 0;
        for (TrackingPlayer player : players) {
            total += player.replenishAirCalls;
        }
        return total;
    }

    private static final class TrackingPlayer extends TestablePlayableSprite {
        private int replenishAirCalls;

        private TrackingPlayer(String code, int x, int y) {
            super(code, (short) x, (short) y);
            setCentreX((short) x);
            setCentreY((short) y);
        }

        @Override
        public void replenishAir() {
            replenishAirCalls++;
            super.replenishAir();
        }

        @Override
        public void replenishAirPreservingRollingStatus() {
            replenishAirCalls++;
            super.replenishAirPreservingRollingStatus();
        }
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;
        private int sfxCalls;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
            withWaterSystem(new AlwaysSubmergedWaterSystem());
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }

        @Override
        public void playSfx(int soundId) {
            sfxCalls++;
        }
    }

    private static final class AlwaysSubmergedWaterSystem extends WaterSystem {
        @Override
        public boolean hasWater(int zoneId, int actId) {
            return true;
        }

        @Override
        public int getWaterLevelY(int zoneId, int actId) {
            return -0x1000;
        }
    }
}
