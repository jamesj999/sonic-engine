package uk.co.jamesj999.sonic.level.objects;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for TouchResponseManager collision detection logic.
 * Tests the overlap detection algorithm and touch response handling.
 */
public class TestTouchResponseManager {

    private TouchResponseManager manager;
    private MockObjectManager objectManager;
    private TouchResponseTable table;
    private AbstractPlayableSprite player;

    @Before
    public void setUp() {
        objectManager = new MockObjectManager();
        // Use Mockito to mock TouchResponseTable since its constructor reads from ROM
        table = mock(TouchResponseTable.class);
        manager = new TouchResponseManager(objectManager, table);

        // Create a mock player using Mockito
        player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 160);
        when(player.getCentreY()).thenReturn((short) 112);
        when(player.getYRadius()).thenReturn((short) 20);
        when(player.getCrouching()).thenReturn(false);
        when(player.getRolling()).thenReturn(false);
        when(player.getSpindash()).thenReturn(false);
        when(player.getInvincibleFrames()).thenReturn(0);
        when(player.getInvulnerable()).thenReturn(false);
        when(player.getDead()).thenReturn(false);
        when(player.getRingCount()).thenReturn(0);
    }

    // ==================== Overlap Detection Tests ====================

    private void setupTableSize(int sizeIndex, int width, int height) {
        when(table.getWidthRadius(sizeIndex)).thenReturn(width);
        when(table.getHeightRadius(sizeIndex)).thenReturn(height);
    }

    @Test
    public void testNoOverlapWhenObjectFarRight() {
        // Object far to the right of player
        MockTouchObject obj = new MockTouchObject(500, 112, 0x08); // Size index 8 = 16x16
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertFalse("Should not overlap when object is far to the right",
                obj.wasTouched);
    }

    @Test
    public void testNoOverlapWhenObjectFarLeft() {
        // Object far to the left of player
        MockTouchObject obj = new MockTouchObject(10, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertFalse("Should not overlap when object is far to the left",
                obj.wasTouched);
    }

    @Test
    public void testNoOverlapWhenObjectFarAbove() {
        // Object far above player
        MockTouchObject obj = new MockTouchObject(160, 10, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertFalse("Should not overlap when object is far above",
                obj.wasTouched);
    }

    @Test
    public void testNoOverlapWhenObjectFarBelow() {
        // Object far below player
        MockTouchObject obj = new MockTouchObject(160, 300, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertFalse("Should not overlap when object is far below",
                obj.wasTouched);
    }

    @Test
    public void testOverlapWhenObjectAtSamePosition() {
        // Object at same position as player
        MockTouchObject obj = new MockTouchObject(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertTrue("Should overlap when object is at player position",
                obj.wasTouched);
    }

    @Test
    public void testOverlapWithLargerObject() {
        // Large object that overlaps player
        MockTouchObject obj = new MockTouchObject(180, 112, 0x10); // Size index 16 = 32x32
        setupTableSize(16, 32, 32);
        objectManager.addObject(obj);

        manager.update(player);

        assertTrue("Should overlap with large object near player",
                obj.wasTouched);
    }

    // ==================== Touch Category Tests ====================

    @Test
    public void testEnemyCategoryDecoding() {
        // Flags 0x00-0x3F = ENEMY category
        MockTouchObject obj = new MockTouchObject(160, 112, 0x08); // 0x08 & 0xC0 = 0x00 = ENEMY
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertEquals("Category should be ENEMY for flags 0x00-0x3F",
                TouchCategory.ENEMY, obj.lastResult.category());
    }

    @Test
    public void testSpecialCategoryDecoding() {
        // Flags 0x40-0x7F = SPECIAL category
        MockTouchObject obj = new MockTouchObject(160, 112, 0x48); // 0x48 & 0xC0 = 0x40 = SPECIAL
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertEquals("Category should be SPECIAL for flags 0x40-0x7F",
                TouchCategory.SPECIAL, obj.lastResult.category());
    }

    @Test
    public void testHurtCategoryDecoding() {
        // Flags 0x80-0xBF = HURT category
        MockTouchObject obj = new MockTouchObject(160, 112, 0x88); // 0x88 & 0xC0 = 0x80 = HURT
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertEquals("Category should be HURT for flags 0x80-0xBF",
                TouchCategory.HURT, obj.lastResult.category());
    }

    @Test
    public void testBossCategoryDecoding() {
        // Flags 0xC0-0xFF = BOSS category
        MockTouchObject obj = new MockTouchObject(160, 112, 0xC8); // 0xC8 & 0xC0 = 0xC0 = BOSS
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertEquals("Category should be BOSS for flags 0xC0-0xFF",
                TouchCategory.BOSS, obj.lastResult.category());
    }

    // ==================== Player State Tests ====================

    @Test
    public void testNoTouchWhenPlayerIsDead() {
        when(player.getDead()).thenReturn(true);
        MockTouchObject obj = new MockTouchObject(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        assertFalse("Should not touch objects when player is dead",
                obj.wasTouched);
    }

    @Test
    public void testCrouchingReducesHitbox() {
        when(player.getCrouching()).thenReturn(true);
        // When crouching, player hitbox is smaller (20 height, shifted down 12px)
        MockTouchObject obj = new MockTouchObject(160, 70, 0x08); // Above player's head
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);

        // Object should NOT touch when player is crouching and object is above normal standing position
        assertFalse("Crouching should reduce hitbox height",
                obj.wasTouched);
    }

    // ==================== Enemy Bounce Tests ====================

    @Test
    public void testEnemyAttackedWhenPlayerRolling() {
        when(player.getRolling()).thenReturn(true); // Attacking state
        when(player.getYSpeed()).thenReturn((short) 500); // Falling
        when(player.getCentreY()).thenReturn((short) 100); // Above enemy

        MockAttackableEnemy enemy = new MockAttackableEnemy(160, 120, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addObject(enemy);

        manager.update(player);

        assertTrue("Enemy should have been attacked when player is rolling", enemy.wasAttacked);
    }

    @Test
    public void testPlayerHurtWhenNotAttacking() {
        when(player.getRolling()).thenReturn(false);
        when(player.getSpindash()).thenReturn(false);
        when(player.getInvincibleFrames()).thenReturn(0);
        when(player.getRingCount()).thenReturn(5);
        when(player.getInvulnerable()).thenReturn(false);
        when(player.hasShield()).thenReturn(false);

        MockTouchObject enemy = new MockTouchObject(160, 112, 0x08); // ENEMY category
        setupTableSize(8, 16, 16);
        objectManager.addObject(enemy);

        manager.update(player);

        // Verify applyHurtOrDeath was called
        verify(player).applyHurtOrDeath(anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testNoHurtWhenInvulnerable() {
        when(player.getInvulnerable()).thenReturn(true);

        MockTouchObject hurtObject = new MockTouchObject(160, 112, 0x88); // HURT category
        setupTableSize(8, 16, 16);
        objectManager.addObject(hurtObject);

        manager.update(player);

        // Verify applyHurtOrDeath was NOT called
        verify(player, never()).applyHurtOrDeath(anyInt(), anyBoolean(), anyBoolean());
    }

    // ==================== Overlap Persistence Tests ====================

    @Test
    public void testTouchOnlyTriggersOncePerOverlap() {
        MockTouchObject obj = new MockTouchObject(160, 112, 0x48); // SPECIAL category
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        // First update - should trigger touch
        manager.update(player);
        assertTrue("First update should trigger touch", obj.wasTouched);

        // Reset touch flag
        obj.wasTouched = false;

        // Second update - still overlapping but should NOT trigger again
        manager.update(player);
        assertFalse("Second update should NOT trigger touch for same overlap",
                obj.wasTouched);
    }

    @Test
    public void testTouchTriggersAgainAfterExitAndReenter() {
        MockTouchObject obj = new MockTouchObject(160, 112, 0x48);
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        // First update - triggers touch
        manager.update(player);
        obj.wasTouched = false;

        // Move player away
        when(player.getCentreX()).thenReturn((short) 500);
        manager.update(player);

        // Move player back
        when(player.getCentreX()).thenReturn((short) 160);
        manager.update(player);

        assertTrue("Touch should trigger again after exit and re-enter",
                obj.wasTouched);
    }

    // ==================== Reset Tests ====================

    @Test
    public void testResetClearsOverlappingSet() {
        MockTouchObject obj = new MockTouchObject(160, 112, 0x48);
        setupTableSize(8, 16, 16);
        objectManager.addObject(obj);

        manager.update(player);
        obj.wasTouched = false;

        // Reset should clear tracking
        manager.reset();

        // Now touch should trigger again
        manager.update(player);
        assertTrue("Touch should trigger after reset even for same overlap",
                obj.wasTouched);
    }

    // ==================== Helper Classes ====================

    /**
     * Mock ObjectManager that doesn't require real dependencies.
     */
    private static class MockObjectManager extends ObjectManager {
        private final List<ObjectInstance> objects = new ArrayList<>();

        public MockObjectManager() {
            super(null, null);
        }

        public void addObject(ObjectInstance obj) {
            objects.add(obj);
        }

        @Override
        public Collection<ObjectInstance> getActiveObjects() {
            return objects;
        }
    }

    /**
     * Mock object that tracks touch events.
     */
    private static class MockTouchObject implements ObjectInstance, TouchResponseProvider, TouchResponseListener {
        private final ObjectSpawn spawn;
        private final int collisionFlags;
        boolean wasTouched = false;
        TouchResponseResult lastResult;

        public MockTouchObject(int x, int y, int flags) {
            this.spawn = new ObjectSpawn(x, y, 0, 0, 0, false, 0);
            this.collisionFlags = flags;
        }

        @Override
        public int getCollisionFlags() {
            return collisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public void onTouchResponse(AbstractPlayableSprite player, TouchResponseResult result, int frameCounter) {
            wasTouched = true;
            lastResult = result;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return spawn;
        }

        @Override
        public void update(int frameCounter, AbstractPlayableSprite player) {}

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {}

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }
    }

    /**
     * Mock attackable enemy for testing attack behavior.
     */
    private static class MockAttackableEnemy extends MockTouchObject implements TouchResponseAttackable {
        boolean wasAttacked = false;

        public MockAttackableEnemy(int x, int y, int flags) {
            super(x, y, flags);
        }

        @Override
        public void onPlayerAttack(AbstractPlayableSprite player, TouchResponseResult result) {
            wasAttacked = true;
        }
    }
}
