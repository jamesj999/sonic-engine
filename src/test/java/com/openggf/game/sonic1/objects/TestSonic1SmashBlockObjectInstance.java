package com.openggf.game.sonic1.objects;

import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1SmashBlockObjectInstance {

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices svc) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(svc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Sonic1SmashBlockObjectInstance createBlock(int x, int y) {
        ObjectServices services = new TestObjectServices()
                .withGameState(GameServices.gameState());
        setConstructionContext(services);
        try {
            Sonic1SmashBlockObjectInstance block = new Sonic1SmashBlockObjectInstance(
                    new ObjectSpawn(x, y, 0x51, 0x00, 0, false, 0));
            block.setServices(services);
            return block;
        } finally {
            clearConstructionContext();
        }
    }

    @Test
    public void breaksWhenLandingFromCachedRollAnimation() {
        GameServices.gameState().resetSession();
        Sonic1SmashBlockObjectInstance block = createBlock(160, 112);
        TestPlayableSprite player = new TestPlayableSprite(28, 38);

        // Pre-collision state this frame: Sonic is airborne in roll animation.
        player.setAnimationId(Sonic1AnimationIds.ROLL);
        player.setRolling(true);
        player.setAir(true);
        block.update(1, player);

        // Landing resolution may clear rolling before onSolidContact callback.
        player.setRolling(false);
        player.setAir(false);
        block.onSolidContact(player, new SolidContact(true, false, false, true, false), 1);

        assertTrue(block.isDestroyed());
        assertEquals(100, GameServices.gameState().getScore());
        assertEquals(2, GameServices.gameState().getItemBonus());
    }

    @Test
    public void doesNotBreakWhenNotRollAnimatingOrRolling() {
        GameServices.gameState().resetSession();
        Sonic1SmashBlockObjectInstance block = createBlock(160, 112);
        TestPlayableSprite player = new TestPlayableSprite(28, 38);

        player.setAnimationId(Sonic1AnimationIds.WALK);
        player.setRolling(false);
        player.setAir(false);
        block.update(1, player);
        block.onSolidContact(player, new SolidContact(true, false, false, true, false), 1);

        assertFalse(block.isDestroyed());
        assertEquals(0, GameServices.gameState().getScore());
    }

    @Test
    public void chainBonusIncreasesAcrossBlocks() {
        GameServices.gameState().resetSession();
        TestPlayableSprite player = new TestPlayableSprite(28, 38);
        player.setAnimationId(Sonic1AnimationIds.ROLL);
        player.setRolling(true);
        player.setAir(true);

        Sonic1SmashBlockObjectInstance first = createBlock(160, 112);
        first.update(1, player);
        player.setRolling(false);
        player.setAir(false);
        first.onSolidContact(player, new SolidContact(true, false, false, true, false), 1);

        assertEquals(100, GameServices.gameState().getScore());
        assertEquals(2, GameServices.gameState().getItemBonus());

        player.setAnimationId(Sonic1AnimationIds.ROLL);
        player.setRolling(true);
        player.setAir(true);
        Sonic1SmashBlockObjectInstance second = createBlock(192, 112);
        second.update(2, player);
        player.setRolling(false);
        player.setAir(false);
        second.onSolidContact(player, new SolidContact(true, false, false, true, false), 2);

        assertEquals(300, GameServices.gameState().getScore()); // 100 + 200
        assertEquals(4, GameServices.gameState().getItemBonus());
    }

    @Test
    public void sixteenthChainAwardUsesSpecialBonus() {
        GameServices.gameState().resetSession();
        // One smash away from special threshold.
        GameServices.gameState().setItemBonus(0x1E);

        Sonic1SmashBlockObjectInstance block = createBlock(160, 112);
        TestPlayableSprite player = new TestPlayableSprite(28, 38);
        player.setAnimationId(Sonic1AnimationIds.ROLL);
        player.setRolling(true);
        player.setAir(true);
        block.update(1, player);
        player.setRolling(false);
        player.setAir(false);
        block.onSolidContact(player, new SolidContact(true, false, false, true, false), 1);

        assertTrue(block.isDestroyed());
        assertEquals(10000, GameServices.gameState().getScore());
        assertEquals(0x20, GameServices.gameState().getItemBonus());
    }

    @Test
    public void landingResetsItemBonusChain() {
        GameServices.gameState().resetSession();
        GameServices.gameState().setItemBonus(6);

        TestPlayableSprite player = new TestPlayableSprite(28, 38);
        player.setAir(true);
        player.setAir(false);

        assertEquals(0, GameServices.gameState().getItemBonus());
    }

}


