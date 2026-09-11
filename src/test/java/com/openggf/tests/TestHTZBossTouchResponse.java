package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.bosses.Sonic2HTZBossInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for HTZ boss collision and touch-response damage behavior.
 */
public class TestHTZBossTouchResponse {

    private static final int HTZ_BOSS_X = 0x3040;
    private static final int HTZ_BOSS_Y = 0x0580;
    private static final int HTZ_BOSS_SIZE_INDEX = 0x32;

    private TouchResponseTable touchTable;
    private ObjectManager objectManager;
    private AbstractPlayableSprite player;
    private Sonic2HTZBossInstance boss;

    @BeforeEach
    public void setUp() throws Exception {
        TestEnvironment.resetAll();
        // Position camera at boss arena so isOnScreenForTouch() passes for the boss.
        // The default camera bounds (x=0, y=0, 320x224) exclude HTZ_BOSS_X=0x3040
        // and HTZ_BOSS_Y=0x0580. ROM parity (BuildSprites Y check, see
        // AbstractObjectInstance#isOnScreenForTouch) requires the camera Y to
        // also be within range so the boss's render flag bit 7 stays set.
        GameServices.camera().setX((short) HTZ_BOSS_X);
        GameServices.camera().setY((short) HTZ_BOSS_Y);
        touchTable = mock(TouchResponseTable.class);
        when(touchTable.getWidthRadius(HTZ_BOSS_SIZE_INDEX)).thenReturn(32);
        when(touchTable.getHeightRadius(HTZ_BOSS_SIZE_INDEX)).thenReturn(32);

        objectManager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, touchTable);


        boss = new Sonic2HTZBossInstance(
                new ObjectSpawn(HTZ_BOSS_X, HTZ_BOSS_Y, Sonic2ObjectIds.HTZ_BOSS, 0, 0, false, 0));

        player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (HTZ_BOSS_X + 8));
        when(player.getCentreY()).thenReturn((short) HTZ_BOSS_Y);
        when(player.getYRadius()).thenReturn((short) 20);
        when(player.getCrouching()).thenReturn(false);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(2);
        when(player.getSpindash()).thenReturn(false);
        when(player.getInvincibleFrames()).thenReturn(0);
        when(player.getInvulnerable()).thenReturn(false);
        when(player.getDead()).thenReturn(false);
        when(player.getRingCount()).thenReturn(0);
        when(player.getXSpeed()).thenReturn((short) 0x200);
        when(player.getYSpeed()).thenReturn((short) -0x300);
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void bossCollisionUsesRomCollisionSizeIndex() {
        int flags = boss.getCollisionFlags();
        assertEquals(0xC0 | HTZ_BOSS_SIZE_INDEX, flags);
        assertEquals(HTZ_BOSS_SIZE_INDEX, flags & 0x3F);
    }

    @Test
    public void attackingTouchResponseDamagesBossAndAppliesBossBounce() {
        assertEquals(8, boss.getState().hitCount);
        objectManager.addDynamicObject(boss);

        objectManager.update(GameServices.camera().getX(), player, List.of(), 1);

        assertEquals(7, boss.getState().hitCount);
        assertTrue(boss.getState().invulnerable);
        // ROM-accurate: boss bounce only modifies velocities, does not set air flag
        verify(player).setXSpeed(eq((short) -0x200));
        verify(player).setYSpeed(eq((short) 0x300));
    }

    @Test
    public void defeatRoutineCountdownStartsOnFrameAfterFinalHit() throws Exception {
        boss.getState().hitCount = 1;
        objectManager.addDynamicObject(boss);

        objectManager.update(GameServices.camera().getX(), player, List.of(), 1);

        assertTrue(boss.getState().defeated);
        assertEquals(0xB3, readPrivateInt(boss, "defeatTimer"));
    }

    @Test
    public void firstFleeFrameAlsoAdvancesCameraRelease() throws Exception {
        boss.getState().defeated = true;
        boss.getState().routineSecondary = 8;
        writePrivateInt(boss, "defeatTimer", -0x3B);
        writePrivateBoolean(boss, "defeatFleeStarted", false);
        GameServices.camera().setMaxX((short) 0x2F5E);
        int initialY = boss.getState().y;

        objectManager.addDynamicObject(boss);
        objectManager.update(GameServices.camera().getX(), player, List.of(), 1);

        // ROM Obj52_Mobile_Flee (docs/s2disasm/s2.asm:64598-64606) falls through to
        // loc_30170 (docs/s2disasm/s2.asm:64608-64612) in the SAME frame it sets
        // Boss_defeated_flag, so y_pos and Camera_Max_X_pos advance immediately.
        assertTrue(GameServices.gameState().isBossDefeatedFlag());
        assertEquals(initialY + 2, boss.getState().y);
        assertEquals((short) 0x2F60, GameServices.camera().getMaxX());

        objectManager.update(GameServices.camera().getX(), player, List.of(), 2);

        assertEquals(initialY + 4, boss.getState().y);
        assertEquals((short) 0x2F62, GameServices.camera().getMaxX());
    }

    @Test
    public void bossCanBeDamagedAfterOverlapStartsWhenAttackBeginsLater() {
        AtomicBoolean rolling = new AtomicBoolean(false);
        AbstractPlayableSprite dynamicPlayer = mock(AbstractPlayableSprite.class);
        when(dynamicPlayer.getCentreX()).thenReturn((short) (HTZ_BOSS_X + 8));
        when(dynamicPlayer.getCentreY()).thenReturn((short) HTZ_BOSS_Y);
        when(dynamicPlayer.getYRadius()).thenReturn((short) 20);
        when(dynamicPlayer.getCrouching()).thenReturn(false);
        when(dynamicPlayer.getRolling()).thenAnswer(invocation -> rolling.get());
        when(dynamicPlayer.getAnimationId()).thenAnswer(invocation -> rolling.get() ? 2 : 0);
        when(dynamicPlayer.getSpindash()).thenReturn(false);
        when(dynamicPlayer.getInvincibleFrames()).thenReturn(0);
        when(dynamicPlayer.getInvulnerable()).thenReturn(false);
        when(dynamicPlayer.getDead()).thenReturn(false);
        when(dynamicPlayer.getRingCount()).thenReturn(0);
        when(dynamicPlayer.getXSpeed()).thenReturn((short) 0x200);
        when(dynamicPlayer.getYSpeed()).thenReturn((short) -0x300);

        objectManager.addDynamicObject(boss);

        // First frame: overlap begins while not attacking.
        objectManager.update(GameServices.camera().getX(), dynamicPlayer, List.of(), 1);
        assertEquals(8, boss.getState().hitCount);

        // Second frame: still overlapping, now attacking.
        rolling.set(true);
        objectManager.update(GameServices.camera().getX(), dynamicPlayer, List.of(), 2);
        assertEquals(7, boss.getState().hitCount);
    }

    @Test
    public void removedBossDoesNotRemainAsATouchCandidateAfterReplacement() {
        objectManager.addDynamicObject(boss);
        objectManager.update(GameServices.camera().getX(), player, List.of(), 1);
        assertEquals(7, boss.getState().hitCount);

        objectManager.removeDynamicObject(boss);

        Sonic2HTZBossInstance replacement = new Sonic2HTZBossInstance(
                new ObjectSpawn(HTZ_BOSS_X, HTZ_BOSS_Y, Sonic2ObjectIds.HTZ_BOSS, 0, 0, false, 0));
        objectManager.addDynamicObject(replacement);
        objectManager.update(GameServices.camera().getX(), player, List.of(), 2);

        assertEquals(7, boss.getState().hitCount,
                "Removed boss should not remain in the touch candidate set");
        assertEquals(7, replacement.getState().hitCount,
                "Replacement boss should still be touch-damageable after add/remove cycles");
    }

    private static final class NoOpObjectRegistry implements ObjectRegistry {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "Test";
        }
    }

    private static int readPrivateInt(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void writePrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void writePrivateBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
