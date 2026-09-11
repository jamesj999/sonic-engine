package com.openggf.sprites.playable;

import com.openggf.game.InstaShieldHandle;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PowerUpObject;
import com.openggf.game.PowerUpSpawner;
import com.openggf.game.ShieldType;
import com.openggf.game.GameServices;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestShieldRewindRestore {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void refreshKeepsLiveMatchingShieldAndRequestsArtRefresh() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        RecordingShield restoredShield = new RecordingShield(ShieldType.FIRE);
        RecordingPowerUpSpawner spawner = new RecordingPowerUpSpawner(restoredShield);
        sprite.setPowerUpSpawner(spawner);
        sprite.giveShield(ShieldType.FIRE);

        sprite.refreshPowerUpObjectsAfterRewindRestore();

        assertSame(restoredShield, sprite.getShieldObject(),
                "A live shield object matching the restored shield type should remain linked");
        assertFalse(restoredShield.isDestroyed(),
                "Refresh must not destroy the restored matching shield object");
        assertEquals(1, restoredShield.artRefreshRequests,
                "Kept shields must force art/DPLC refresh after rewind restore");
        assertEquals(1, spawner.spawnShieldCalls,
                "The existing matching shield should be kept instead of respawned");
    }

    @Test
    void refreshRespawnsMissingShieldAndRequestsArtRefresh() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        RecordingShield spawnedShield = new RecordingShield(ShieldType.LIGHTNING);
        RecordingPowerUpSpawner spawner = new RecordingPowerUpSpawner(spawnedShield);
        sprite.setPowerUpSpawner(spawner);
        sprite.setShieldStateForTest(true, ShieldType.LIGHTNING);

        sprite.refreshPowerUpObjectsAfterRewindRestore();

        assertSame(spawnedShield, sprite.getShieldObject());
        assertEquals(1, spawnedShield.artRefreshRequests,
                "Respawned shields must also force art/DPLC refresh after rewind restore");
        assertEquals(1, spawner.spawnShieldCalls);
    }

    @Test
    void refreshRespawnsDestroyedShieldAndRequestsArtRefresh() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        RecordingShield destroyedShield = new RecordingShield(ShieldType.BUBBLE);
        RecordingShield replacementShield = new RecordingShield(ShieldType.BUBBLE);
        RecordingPowerUpSpawner spawner = new RecordingPowerUpSpawner(destroyedShield, replacementShield);
        sprite.setPowerUpSpawner(spawner);
        sprite.giveShield(ShieldType.BUBBLE);
        destroyedShield.destroy();

        sprite.refreshPowerUpObjectsAfterRewindRestore();

        assertSame(replacementShield, sprite.getShieldObject());
        assertTrue(destroyedShield.isDestroyed());
        assertEquals(1, replacementShield.artRefreshRequests,
                "Replacement shields must force art/DPLC refresh after rewind restore");
        assertEquals(2, spawner.spawnShieldCalls);
    }

    @Test
    void refreshRespawnsWrongTypeShieldAndRequestsArtRefresh() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        RecordingShield wrongTypeShield = new RecordingShield(ShieldType.BUBBLE);
        RecordingShield replacementShield = new RecordingShield(ShieldType.FIRE);
        RecordingPowerUpSpawner spawner = new RecordingPowerUpSpawner(wrongTypeShield, replacementShield);
        sprite.setPowerUpSpawner(spawner);
        sprite.giveShield(ShieldType.FIRE);

        sprite.refreshPowerUpObjectsAfterRewindRestore();

        assertSame(replacementShield, sprite.getShieldObject());
        assertTrue(wrongTypeShield.isDestroyed(),
                "A live shield object for a different type should be discarded");
        assertEquals(1, replacementShield.artRefreshRequests,
                "Replacement shields must force art/DPLC refresh after rewind restore");
        assertEquals(2, spawner.spawnShieldCalls);
    }

    @Test
    void refreshRelinksObjectManagerShieldForSamePlayerAndRequestsArtRefresh() throws Exception {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        ManagedRecordingShield restoredShield = new ManagedRecordingShield(sprite, ShieldType.FIRE);
        RecordingShield fallbackShield = new RecordingShield(ShieldType.FIRE);
        RecordingPowerUpSpawner spawner = new RecordingPowerUpSpawner(fallbackShield);
        sprite.setPowerUpSpawner(spawner);
        sprite.setShieldStateForTest(true, ShieldType.FIRE);
        installObjectManager().addDynamicObject(restoredShield);

        sprite.refreshPowerUpObjectsAfterRewindRestore();

        assertSame(restoredShield, sprite.getShieldObject(),
                "Rewind refresh should relink the matching player-owned shield restored by ObjectManager");
        assertEquals(1, restoredShield.artRefreshRequests,
                "Relinked shields must force art/DPLC refresh after rewind restore");
        assertEquals(0, spawner.spawnShieldCalls,
                "Relinking a restored shield should not respawn a replacement");
    }

    @Test
    void refreshDoesNotRelinkNonShieldPowerUpForBasicShield() throws Exception {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        ManagedNonShieldPowerUp invincibilityLike = new ManagedNonShieldPowerUp();
        RecordingShield replacementShield = new RecordingShield(ShieldType.BASIC);
        RecordingPowerUpSpawner spawner = new RecordingPowerUpSpawner(replacementShield);
        sprite.setPowerUpSpawner(spawner);
        sprite.setShieldStateForTest(true, ShieldType.BASIC);
        installObjectManager().addDynamicObject(invincibilityLike);

        sprite.refreshPowerUpObjectsAfterRewindRestore();

        assertSame(replacementShield, sprite.getShieldObject(),
                "A non-shield PowerUpObject must not be relinked as the restored basic shield");
        assertEquals(0, invincibilityLike.artRefreshRequests,
                "Rejected non-shield power-ups must not receive shield art refresh");
        assertEquals(1, replacementShield.artRefreshRequests);
        assertEquals(1, spawner.spawnShieldCalls);
    }

    @Test
    void refreshDoesNotRelinkSameTypeShieldOwnedByDifferentPlayer() throws Exception {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        TestablePlayableSprite otherPlayer = new TestablePlayableSprite("tails", (short) 120, (short) 200);
        ManagedRecordingShield otherPlayerShield = new ManagedRecordingShield(otherPlayer, ShieldType.LIGHTNING);
        RecordingShield replacementShield = new RecordingShield(ShieldType.LIGHTNING);
        RecordingPowerUpSpawner spawner = new RecordingPowerUpSpawner(replacementShield);
        sprite.setPowerUpSpawner(spawner);
        sprite.setShieldStateForTest(true, ShieldType.LIGHTNING);
        installObjectManager().addDynamicObject(otherPlayerShield);

        sprite.refreshPowerUpObjectsAfterRewindRestore();

        assertSame(replacementShield, sprite.getShieldObject());
        assertNotSame(otherPlayerShield, sprite.getShieldObject(),
                "Rewind refresh must not steal another player's shield object");
        assertEquals(0, otherPlayerShield.artRefreshRequests);
        assertEquals(1, replacementShield.artRefreshRequests);
        assertEquals(1, spawner.spawnShieldCalls);
    }

    private static ObjectManager installObjectManager() throws Exception {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = new ObjectManager(List.of(), null, 0, null, null);
        Field field = LevelManager.class.getDeclaredField("objectManager");
        field.setAccessible(true);
        field.set(levelManager, objectManager);
        return objectManager;
    }

    private static final class RecordingPowerUpSpawner implements PowerUpSpawner {
        private final Queue<PowerUpObject> shields = new ArrayDeque<>();
        private int spawnShieldCalls;

        private RecordingPowerUpSpawner(PowerUpObject... shields) {
            this.shields.addAll(java.util.List.of(shields));
        }

        @Override
        public PowerUpObject spawnShield(PlayableEntity player, ShieldType type) {
            spawnShieldCalls++;
            return shields.poll();
        }

        @Override
        public PowerUpObject spawnInvincibilityStars(PlayableEntity player) {
            return null;
        }

        @Override
        public InstaShieldHandle createInstaShield(PlayableEntity player) {
            return null;
        }

        @Override
        public void registerObject(PowerUpObject obj) {
        }

        @Override
        public void spawnSplash(PlayableEntity player) {
        }
    }

    private static final class RecordingShield implements PowerUpObject {
        private final ShieldType type;
        private boolean destroyed;
        private int artRefreshRequests;

        private RecordingShield(ShieldType type) {
            this.type = type;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public boolean isDestroyed() {
            return destroyed;
        }

        @Override
        public void setVisible(boolean visible) {
        }

        @Override
        public boolean matchesShieldType(ShieldType restoredType) {
            return type == restoredType;
        }

        @Override
        public boolean isShieldFor(PlayableEntity player, ShieldType restoredType) {
            return matchesShieldType(restoredType);
        }

        @Override
        public void refreshArtAfterRewindRestore() {
            artRefreshRequests++;
        }
    }

    private static final class ManagedRecordingShield extends AbstractObjectInstance implements PowerUpObject {
        private final PlayableEntity owner;
        private final ShieldType type;
        private int artRefreshRequests;

        private ManagedRecordingShield(PlayableEntity owner, ShieldType type) {
            super(null, "ManagedRecordingShield");
            this.owner = owner;
            this.type = type;
        }

        @Override
        public void destroy() {
            setDestroyed(true);
        }

        @Override
        public void setVisible(boolean visible) {
        }

        @Override
        public boolean matchesShieldType(ShieldType restoredType) {
            return type == restoredType;
        }

        @Override
        public boolean isShieldFor(PlayableEntity player, ShieldType restoredType) {
            return owner == player && matchesShieldType(restoredType);
        }

        @Override
        public void refreshArtAfterRewindRestore() {
            artRefreshRequests++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class ManagedNonShieldPowerUp extends AbstractObjectInstance implements PowerUpObject {
        private int artRefreshRequests;

        private ManagedNonShieldPowerUp() {
            super(null, "ManagedNonShieldPowerUp");
        }

        @Override
        public void destroy() {
            setDestroyed(true);
        }

        @Override
        public void setVisible(boolean visible) {
        }

        @Override
        public void refreshArtAfterRewindRestore() {
            artRefreshRequests++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
