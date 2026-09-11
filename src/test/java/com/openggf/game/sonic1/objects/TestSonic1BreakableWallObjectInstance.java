package com.openggf.game.sonic1.objects;

import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1BreakableWallObjectInstance {

    @Test
    void updateAppliesCheckpointContactToPlayersFromObjectPlayerQuery() {
        TestPlayableSprite main = new TestPlayableSprite();
        TestPlayableSprite sidekick = rollingPlayerAt(0x0F00);
        sidekick.setCpuControlled(true);

        TestableSonic1BreakableWallObjectInstance wall = new TestableSonic1BreakableWallObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x3C, 0, 0, false, 0));
        wall.setCheckpointBatch(new SolidCheckpointBatch(wall, Map.of(
                main, noContact(),
                sidekick, sideContact(0x0480)
        )));
        wall.setServices(new TestObjectServices() {
            private final ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(
                    () -> main,
                    () -> List.of(sidekick));

            @Override
            public ObjectPlayerQuery playerQuery() {
                return playerQuery;
            }
        });

        wall.update(1, main);

        assertTrue(wall.isDestroyed(),
                "Breakable wall should use ObjectPlayerQuery participants instead of the raw sidekick list");
    }

    @Test
    void sideBreakUsesRollAnimationSnapshotNotRollingStatus() {
        TestPlayableSprite player = rollingPlayerAt(0x0F00);
        player.setRolling(false);
        player.setAnimationId(Sonic1AnimationIds.ROLL.id());

        TestableSonic1BreakableWallObjectInstance wall = new TestableSonic1BreakableWallObjectInstance(
                new ObjectSpawn(0x1000, 0x1000, 0x3C, 0, 0, false, 0));
        wall.setCheckpointBatch(new SolidCheckpointBatch(wall, Map.of(
                player, sideContact(0x0480, false, Sonic1AnimationIds.ROLL.id())
        )));
        wall.setServices(new TestObjectServices() {
            private final ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(
                    () -> player,
                    List::of);

            @Override
            public ObjectPlayerQuery playerQuery() {
                return playerQuery;
            }
        });

        wall.update(1, player);

        assertTrue(wall.isDestroyed(),
                "S1 Smashable Wall checks obAnim(a1)==id_Roll after SolidObject, not status rolling");
    }

    private static TestPlayableSprite rollingPlayerAt(int centreX) {
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) centreX);
        player.setCentreY((short) 0x1000);
        player.setAir(false);
        player.setRolling(true);
        player.setAnimationId(Sonic1AnimationIds.ROLL.id());
        player.setXSpeed((short) 0x0480);
        return player;
    }

    private static PlayerSolidContactResult noContact() {
        return new PlayerSolidContactResult(ContactKind.NONE, false, false, false, false,
                PreContactState.ZERO, null, 0);
    }

    private static PlayerSolidContactResult sideContact(int xSpeed) {
        return sideContact(xSpeed, true, Sonic1AnimationIds.ROLL.id());
    }

    private static PlayerSolidContactResult sideContact(int xSpeed, boolean rolling, int animationId) {
        return new PlayerSolidContactResult(ContactKind.SIDE, false, false, true, false,
                new PreContactState((short) xSpeed, (short) 0, rolling, animationId), null, 0);
    }

    private static final class TestableSonic1BreakableWallObjectInstance extends Sonic1BreakableWallObjectInstance {
        private SolidCheckpointBatch checkpointBatch;

        private TestableSonic1BreakableWallObjectInstance(ObjectSpawn spawn) {
            super(spawn);
        }

        private void setCheckpointBatch(SolidCheckpointBatch checkpointBatch) {
            this.checkpointBatch = checkpointBatch;
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            return checkpointBatch;
        }
    }
}
