package com.openggf.level.objects;

import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.graphics.GLCommand;
import org.mockito.Mockito;
import com.openggf.game.DamageCause;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.GameRules;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.game.PlayableEntity;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.camera.Camera;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for touch response collision detection logic.
 * Tests the overlap detection algorithm and touch response handling.
 */
public class TestTouchResponseManager {

    private ObjectManager objectManager;
    private TouchResponseTable table;
    private AbstractPlayableSprite player;

    @BeforeEach
    public void setUp() {
        // Use Mockito to mock TouchResponseTable since its constructor reads from ROM
        table = Mockito.mock(TouchResponseTable.class);
        DebugOverlayManager debugOverlay = mock(DebugOverlayManager.class);
        when(debugOverlay.isEnabled(any())).thenReturn(false);
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectServices services = new TestObjectServices()
                .withDebugOverlay(debugOverlay)
                .withCamera(camera);
        objectManager = new ObjectManager(List.of(), new ObjectRegistry() {
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
        }, 0, null, table, null, camera, services);
        objectManager.resetTouchResponses();

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

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
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
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(obj.wasTouched, "Should not overlap when object is far to the right");
    }

    @Test
    public void testNoOverlapWhenObjectFarLeft() {
        // Object far to the left of player
        MockTouchObject obj = new MockTouchObject(10, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(obj.wasTouched, "Should not overlap when object is far to the left");
    }

    @Test
    public void testNoOverlapWhenObjectFarAbove() {
        // Object far above player
        MockTouchObject obj = new MockTouchObject(160, 10, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(obj.wasTouched, "Should not overlap when object is far above");
    }

    @Test
    public void testNoOverlapWhenObjectFarBelow() {
        // Object far below player
        MockTouchObject obj = new MockTouchObject(160, 300, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(obj.wasTouched, "Should not overlap when object is far below");
    }

    @Test
    public void testOverlapWhenObjectAtSamePosition() {
        // Object at same position as player
        MockTouchObject obj = new MockTouchObject(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertTrue(obj.wasTouched, "Should overlap when object is at player position");
    }

    @Test
    public void testOverlapWithLargerObject() {
        // Large object that overlaps player
        MockTouchObject obj = new MockTouchObject(180, 112, 0x10); // Size index 16 = 32x32
        setupTableSize(16, 32, 32);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertTrue(obj.wasTouched, "Should overlap with large object near player");
    }

    // ==================== Touch Category Tests ====================

    @Test
    public void testEnemyCategoryDecoding() {
        // Flags 0x00-0x3F = ENEMY category
        MockTouchObject obj = new MockTouchObject(160, 112, 0x08); // 0x08 & 0xC0 = 0x00 = ENEMY
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertEquals(TouchCategory.ENEMY, obj.lastResult.category(), "Category should be ENEMY for flags 0x00-0x3F");
    }

    @Test
    public void testSpecialCategoryDecoding() {
        // Flags 0x40-0x7F = SPECIAL category
        MockTouchObject obj = new MockTouchObject(160, 112, 0x48); // 0x48 & 0xC0 = 0x40 = SPECIAL
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertEquals(TouchCategory.SPECIAL, obj.lastResult.category(), "Category should be SPECIAL for flags 0x40-0x7F");
    }

    @Test
    public void testHurtCategoryDecoding() {
        // Flags 0x80-0xBF = HURT category
        MockTouchObject obj = new MockTouchObject(160, 112, 0x88); // 0x88 & 0xC0 = 0x80 = HURT
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertEquals(TouchCategory.HURT, obj.lastResult.category(), "Category should be HURT for flags 0x80-0xBF");
    }

    @Test
    public void testBossCategoryDecoding() {
        // Flags 0xC0-0xFF = BOSS category
        MockTouchObject obj = new MockTouchObject(160, 112, 0xC8); // 0xC8 & 0xC0 = 0xC0 = BOSS
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertEquals(TouchCategory.BOSS, obj.lastResult.category(), "Category should be BOSS for flags 0xC0-0xFF");
    }

    @Test
    public void testS3kTouchSpecialPropertyFlagDecoding() {
        // S3K Touch_Special treats 0xC0|$17 as collision_property signaling,
        // not boss damage/bounce handling.
        MockS3kTouchSpecialObject obj = new MockS3kTouchSpecialObject(160, 112, 0xD7);
        setupTableSize(0x17, 8, 8);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertEquals(TouchCategory.SPECIAL, obj.lastResult.category(),
                "S3K Touch_Special property indices must dispatch as listener-only special callbacks");
        assertEquals(0x17, obj.lastResult.sizeIndex());
    }

    @Test
    public void testS3kTouchSpecialUnlistedC0FlagDoesNotDecodeAsBoss() {
        // ROM Touch_ChkValue routes all $C0 flags to Touch_Special
        // (sonic3k.asm:20773-20778). Touch_Special only mutates
        // collision_property for listed sizes; unlisted size $0F returns
        // without boss handling (sonic3k.asm:21162-21183).
        MockS3kTouchSpecialObject obj = new MockS3kTouchSpecialObject(160, 112, 0xCF);
        setupTableSize(0x0F, 24, 24);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertEquals(TouchCategory.SPECIAL, obj.lastResult.category(),
                "S3K $C0 unlisted touch flags must be Touch_Special no-op, not generic boss bounce");
        assertEquals(0x0F, obj.lastResult.sizeIndex());
    }

    @Test
    public void testCnzBalloonDoesNotFireFromDistantPlayer() {
        // Reproduce the latent CNZ-balloon false-positive from the AIZ F6313 round-16
        // diagnostic: ROM-accurate Tails at (0x09E1, 0x0658) vs balloon at
        // (0x0A78, 0x068C) — a 151px X-distance that should NOT overlap with
        // Touch_Sizes[$17] = (8, 8). ROM Obj_CNZBalloon at sonic3k.asm:66747.
        when(player.getCentreX()).thenReturn((short) 0x09E1);
        when(player.getCentreY()).thenReturn((short) 0x0658);
        when(player.getYRadius()).thenReturn((short) 15); // Tails standYRadius
        when(player.getCrouching()).thenReturn(false);

        MockS3kTouchSpecialObject balloon = new MockS3kTouchSpecialObject(0x0A78, 0x068C, 0xD7);
        setupTableSize(0x17, 8, 8);
        objectManager.addDynamicObject(balloon);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(balloon.wasTouched,
                "ROM Touch_Sizes[$17] = (8, 8) — a balloon 151px away in X must not fire onTouchResponse");
    }

    @Test
    public void testTouchResponseSkippedWhenObjectControlSuppresses() {
        // ROM Sonic_Display (sonic3k.asm:22019-22021) and Tails_Display
        // (sonic3k.asm:26263-26266) skip the TouchResponse pass when
        // object_control's bit-7-equivalent is set. Engine's
        // PlayableEntity#isTouchResponseSuppressedByObjectControl() exposes
        // this gate. Without it, sprites in CATCH_UP_FLIGHT or
        // FLIGHT_AUTO_RECOVERY (object_control=$81) fire false-positive
        // touch collisions that ROM never runs.
        when(player.isTouchResponseSuppressedByObjectControl()).thenReturn(true);

        // Place an object directly under the player so an unsuppressed pass
        // would definitely overlap.
        MockTouchObject obj = new MockTouchObject(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(obj.wasTouched,
                "Touch response must be skipped when object_control suppresses it (ROM bit-7 gate)");
    }

    @Test
    public void testSidekickTouchResponseSkippedWhenObjectControlSuppresses() {
        // ROM Tails_Display (sonic3k.asm:26263-26266) skips TouchResponse for
        // Tails when object_control bit 7 is set. This is the path
        // Tails_Catch_Up_Flying (sonic3k.asm:26511) and Tails_FlySwim_Unknown
        // (sonic3k.asm:26542) take when entering CATCH_UP_FLIGHT and
        // FLIGHT_AUTO_RECOVERY — both write object_control=$81. Engine's
        // sidekick CPU controller mirrors that via setObjectControlled(true)
        // without setObjectControlAllowsCpu(true).
        AbstractPlayableSprite sidekick = mock(AbstractPlayableSprite.class);
        when(sidekick.getCentreX()).thenReturn((short) 160);
        when(sidekick.getCentreY()).thenReturn((short) 112);
        when(sidekick.getYRadius()).thenReturn((short) 15);
        when(sidekick.getCrouching()).thenReturn(false);
        when(sidekick.getDead()).thenReturn(false);
        when(sidekick.isDebugMode()).thenReturn(false);
        when(sidekick.isTouchResponseSuppressedByObjectControl()).thenReturn(true);

        // Distant leader so the sidekick is the only candidate for the touch
        // hit; place the object on top of the sidekick.
        when(player.getCentreX()).thenReturn((short) 500);
        MockTouchObject obj = new MockTouchObject(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(sidekick), 1);

        assertFalse(obj.wasTouched,
                "Sidekick touch response must be skipped during CATCH_UP_FLIGHT / FLIGHT_AUTO_RECOVERY");
    }

    @Test
    public void testCnzBalloonContinuousNonOverlapDoesNotFire() {
        // Same scenario but with requiresContinuousTouchCallbacks() = true (matches
        // CnzBalloonInstance behaviour). ROM Obj_CNZBalloon's main routine reads
        // collision_property each frame and only branches into sub_317AE (launch)
        // when Touch_Process set the bit. Engine must mirror this — continuous
        // callbacks must not fire when there is no overlap.
        when(player.getCentreX()).thenReturn((short) 0x09E1);
        when(player.getCentreY()).thenReturn((short) 0x0658);
        when(player.getYRadius()).thenReturn((short) 15);
        when(player.getCrouching()).thenReturn(false);

        MockContinuousS3kTouchSpecialObject balloon =
                new MockContinuousS3kTouchSpecialObject(0x0A78, 0x068C, 0xD7);
        setupTableSize(0x17, 8, 8);
        objectManager.addDynamicObject(balloon);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(balloon.wasTouched,
                "Continuous-callback objects must respect overlap math; non-overlap must not fire");
    }

    // ==================== Player State Tests ====================

    @Test
    public void testNoTouchWhenPlayerIsDead() {
        when(player.getDead()).thenReturn(true);
        MockTouchObject obj = new MockTouchObject(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(obj.wasTouched, "Should not touch objects when player is dead");
    }

    @Test
    public void testCrouchingReducesHitbox() {
        when(player.getCrouching()).thenReturn(true);
        // When crouching, player hitbox is smaller (20 height, shifted down 12px)
        MockTouchObject obj = new MockTouchObject(160, 70, 0x08); // Above player's head
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        // Object should NOT touch when player is crouching and object is above normal standing position
        assertFalse(obj.wasTouched, "Crouching should reduce hitbox height");
    }

    // ==================== Enemy Bounce Tests ====================

    @Test
    public void testEnemyAttackedWhenPlayerRolling() {
        when(player.getRolling()).thenReturn(true); // Attacking state
        when(player.getAnimationId()).thenReturn(0x02);
        when(player.getYSpeed()).thenReturn((short) 500); // Falling
        when(player.getCentreY()).thenReturn((short) 100); // Above enemy

        MockAttackableEnemy enemy = new MockAttackableEnemy(160, 120, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 1);

        assertTrue(enemy.wasAttacked, "Enemy should have been attacked when player is rolling");
    }

    @Test
    public void typedPlayerCapabilityRuleExpandsInstaShieldTouchHitboxWhenBaseRulesDiffer() {
        GameRules base = GameRules.SONIC_1;
        GameRules typedRules = new GameRules(
                base.playerMovement(),
                GameRules.SONIC_3K.playerCapability(),
                base.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble());
        when(player.getGameRules()).thenReturn(GameRules.SONIC_1);
        when(player.getGameRules()).thenReturn(typedRules);
        when(player.getDoubleJumpFlag()).thenReturn(1);
        when(player.getShieldType()).thenReturn(null);
        when(player.getInvincibleFrames()).thenReturn(0);

        MockAttackableEnemy enemy = new MockAttackableEnemy(143, 112, 0x08);
        setupTableSize(8, 8, 8);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 1);

        assertTrue(enemy.wasAttacked,
                "Object touch should prefer typed PlayerCapabilityRules for the insta-shield hitbox");
    }

    @Test
    public void playerCapabilityRuleUsesDefaultWhenTypedCapabilityGroupMissingForTouch() {
        GameRules base = GameRules.SONIC_3K;
        GameRules rulesWithoutCapabilityGroup = new GameRules(
                base.playerMovement(),
                null,
                base.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble());
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getGameRules()).thenReturn(rulesWithoutCapabilityGroup);
        when(player.getDoubleJumpFlag()).thenReturn(1);
        when(player.getShieldType()).thenReturn(null);
        when(player.getInvincibleFrames()).thenReturn(0);

        MockAttackableEnemy enemy = new MockAttackableEnemy(143, 112, 0x08);
        setupTableSize(8, 8, 8);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(enemy.wasAttacked,
                "A null PlayerCapabilityRules group should not recreate removed feature-set insta-shield rules");
    }

    @Test
    public void typedPlayerCapabilityRuleEnablesKnucklesAbilityAttack() {
        Knuckles knuckles = mockKnucklesTouchPlayer(
                gameRulesWithPlayerCapability(GameRules.SONIC_1, GameRules.SONIC_3K));
        when(knuckles.getDoubleJumpFlag()).thenReturn(3);

        MockAttackableEnemy enemy = new MockAttackableEnemy(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, knuckles, List.of(), 1);

        assertTrue(enemy.wasAttacked,
                "Typed PlayerCapabilityRules.elementalShieldsEnabled should enable Knuckles ability attacks");
    }

    @Test
    public void typedPlayerCapabilityRuleDisablesKnucklesAbilityAttack() {
        Knuckles knuckles = mockKnucklesTouchPlayer(
                gameRulesWithPlayerCapability(GameRules.SONIC_3K, GameRules.SONIC_1));
        when(knuckles.getDoubleJumpFlag()).thenReturn(3);

        MockAttackableEnemy enemy = new MockAttackableEnemy(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, knuckles, List.of(), 1);

        assertFalse(enemy.wasAttacked,
                "Typed PlayerCapabilityRules.elementalShieldsEnabled=false should keep ability attacks disabled");
    }

    @Test
    public void typedObjectInteractionRuleHalvesBossBounceWhenBaseRulesDiffer() {
        GameRules base = GameRules.SONIC_2;
        GameRules typedRules = new GameRules(
                base.playerMovement(),
                base.playerCapability(),
                base.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                GameRules.SONIC_1.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble());
        when(player.getGameRules()).thenReturn(GameRules.SONIC_2);
        when(player.getGameRules()).thenReturn(typedRules);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(ObjectManager.ANIM_ROLL);
        when(player.getXSpeed()).thenReturn((short) 0x0400);
        when(player.getYSpeed()).thenReturn((short) -0x0600);

        MockAttackableEnemy boss = new MockAttackableEnemy(160, 112, 0xC8);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(boss);

        objectManager.update(0, player, List.of(), 1);

        assertTrue(boss.wasAttacked, "Boss should be attacked before bounce rules are applied");
        verify(player).setXSpeed((short) -0x0200);
        verify(player).setYSpeed((short) 0x0300);
    }

    @Test
    public void objectInteractionRuleUsesDefaultWhenTypedGroupMissingForBossBounce() {
        GameRules base = GameRules.SONIC_1;
        GameRules rulesWithoutObjectInteractionGroup = new GameRules(
                base.playerMovement(),
                base.playerCapability(),
                base.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                null,
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble());
        when(player.getGameRules()).thenReturn(GameRules.SONIC_1);
        when(player.getGameRules()).thenReturn(rulesWithoutObjectInteractionGroup);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(ObjectManager.ANIM_ROLL);
        when(player.getXSpeed()).thenReturn((short) 0x0400);
        when(player.getYSpeed()).thenReturn((short) -0x0600);

        MockAttackableEnemy boss = new MockAttackableEnemy(160, 112, 0xC8);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(boss);

        objectManager.update(0, player, List.of(), 1);

        assertTrue(boss.wasAttacked, "Boss should be attacked before bounce rules are applied");
        verify(player).setXSpeed((short) -0x0400);
        verify(player).setYSpeed((short) 0x0600);
    }

    @Test
    public void testS3kSidekickRollingStatusWithoutRollAnimationDoesNotAttackEnemy() {
        when(player.getCentreX()).thenReturn((short) 500);

        AbstractPlayableSprite sidekick = mock(AbstractPlayableSprite.class);
        when(sidekick.getCentreX()).thenReturn((short) 160);
        when(sidekick.getCentreY()).thenReturn((short) 112);
        when(sidekick.getYRadius()).thenReturn((short) 20);
        when(sidekick.getCrouching()).thenReturn(false);
        when(sidekick.getDead()).thenReturn(false);
        when(sidekick.getInvulnerable()).thenReturn(false);
        when(sidekick.getInvincibleFrames()).thenReturn(0);
        when(sidekick.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(sidekick.getRolling()).thenReturn(true);
        when(sidekick.getAnimationId()).thenReturn(Sonic3kAnimationIds.WALK.id());

        MockAttackableEnemy enemy = new MockAttackableEnemy(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(sidekick), 1);

        assertFalse(enemy.wasAttacked,
                "S3K Touch_Enemy uses anim=$02/$09, so a stale rolling status bit alone must not kill enemies");
        verify(sidekick).applyHurt(anyInt());
    }

    @Test
    public void sidekickStaleRollAnimationWithoutRollingStatusDoesNotAttackBoss() {
        when(player.getCentreX()).thenReturn((short) 500);

        AbstractPlayableSprite sidekick = mock(AbstractPlayableSprite.class);
        when(sidekick.getCentreX()).thenReturn((short) 160);
        when(sidekick.getCentreY()).thenReturn((short) 112);
        when(sidekick.getYRadius()).thenReturn((short) 20);
        when(sidekick.getCrouching()).thenReturn(false);
        when(sidekick.getDead()).thenReturn(false);
        when(sidekick.getInvulnerable()).thenReturn(false);
        when(sidekick.getInvincibleFrames()).thenReturn(0);
        when(sidekick.getGameRules()).thenReturn(GameRules.SONIC_2);
        when(sidekick.getRolling()).thenReturn(false);
        when(sidekick.getAnimationId()).thenReturn(ObjectManager.ANIM_ROLL);

        MockAttackableEnemy boss = new MockAttackableEnemy(160, 112, 0xC8);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(boss);

        objectManager.update(0, player, List.of(sidekick), 1);

        assertFalse(boss.wasAttacked,
                "A stale roll animation byte must not make a non-rolling sidekick attack a boss");
        verify(sidekick).applyHurt(anyInt());
    }

    @Test
    public void testSidekickTouchResponseStopsAfterFirstOverlappingObject() {
        when(player.getCentreX()).thenReturn((short) 500);

        AbstractPlayableSprite sidekick = mock(AbstractPlayableSprite.class);
        when(sidekick.getCentreX()).thenReturn((short) 160);
        when(sidekick.getCentreY()).thenReturn((short) 112);
        when(sidekick.getYRadius()).thenReturn((short) 20);
        when(sidekick.getCrouching()).thenReturn(false);
        when(sidekick.getDead()).thenReturn(false);
        when(sidekick.getInvulnerable()).thenReturn(false);
        when(sidekick.getInvincibleFrames()).thenReturn(0);
        when(sidekick.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(sidekick.getAnimationId()).thenReturn(Sonic3kAnimationIds.ROLL.id());

        MockTouchObject firstOverlap = new MockTouchObject(160, 112, 0x48);
        MockAttackableEnemy laterEnemy = new MockAttackableEnemy(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(firstOverlap);
        objectManager.addDynamicObject(laterEnemy);

        objectManager.update(0, player, List.of(sidekick), 1);

        assertTrue(firstOverlap.wasTouched, "Sidekick should process the first overlapping object");
        assertFalse(laterEnemy.wasAttacked,
                "ReactToItem returns after the first overlap, so later enemies must not be scanned");
    }

    @Test
    public void sidekickTouchResponseSkipsMainOnlyObjectBeforeConsumingOverlap() {
        when(player.getCentreX()).thenReturn((short) 500);

        AbstractPlayableSprite sidekick = mock(AbstractPlayableSprite.class);
        when(sidekick.getCentreX()).thenReturn((short) 160);
        when(sidekick.getCentreY()).thenReturn((short) 112);
        when(sidekick.getYRadius()).thenReturn((short) 20);
        when(sidekick.getCrouching()).thenReturn(false);
        when(sidekick.getDead()).thenReturn(false);
        when(sidekick.getInvulnerable()).thenReturn(false);
        when(sidekick.getInvincibleFrames()).thenReturn(0);
        when(sidekick.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(sidekick.getAnimationId()).thenReturn(Sonic3kAnimationIds.WALK.id());

        MockMainOnlyTouchObject mainOnlyOverlap = new MockMainOnlyTouchObject(160, 112, 0x48);
        MockTouchObject laterHurt = new MockTouchObject(160, 112, 0x88);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(mainOnlyOverlap);
        objectManager.addDynamicObject(laterHurt);

        objectManager.update(0, player, List.of(sidekick), 1);

        assertFalse(mainOnlyOverlap.wasTouched,
                "MAIN_ONLY touch objects are not candidates for sidekick response");
        assertTrue(laterHurt.wasTouched,
                "An inapplicable earlier overlap must not hide a later sidekick hurt object");
        verify(sidekick).applyHurt(anyInt());
    }

    @Test
    public void testSidekickMultiRegionTouchResponseStopsAfterFirstOverlappingObject() {
        when(player.getCentreX()).thenReturn((short) 500);

        AbstractPlayableSprite sidekick = mock(AbstractPlayableSprite.class);
        when(sidekick.getCentreX()).thenReturn((short) 160);
        when(sidekick.getCentreY()).thenReturn((short) 112);
        when(sidekick.getYRadius()).thenReturn((short) 20);
        when(sidekick.getCrouching()).thenReturn(false);
        when(sidekick.getDead()).thenReturn(false);
        when(sidekick.getInvulnerable()).thenReturn(false);
        when(sidekick.getInvincibleFrames()).thenReturn(0);
        when(sidekick.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(sidekick.getAnimationId()).thenReturn(Sonic3kAnimationIds.ROLL.id());

        MockMultiRegionTouchObject firstOverlap = new MockMultiRegionTouchObject(
                new TouchResponseProvider.TouchRegion(160, 112, 0x48, 0));
        MockMultiRegionTouchObject laterOverlap = new MockMultiRegionTouchObject(
                new TouchResponseProvider.TouchRegion(160, 112, 0x48, 0));
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(firstOverlap);
        objectManager.addDynamicObject(laterOverlap);

        objectManager.update(0, player, List.of(sidekick), 1);

        assertTrue(firstOverlap.wasTouched, "Sidekick should process the first overlapping multi-region object");
        assertFalse(laterOverlap.wasTouched,
                "ReactToItem returns after the first multi-region overlap, so later objects must not be scanned");
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
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 1);

        // Verify applyHurtOrDeath was called (DamageCause overload)
        verify(player).applyHurtOrDeath(anyInt(),
                any(DamageCause.class), anyBoolean());
    }

    @Test
    public void testNoHurtWhenInvulnerable() {
        when(player.getInvulnerable()).thenReturn(true);

        MockTouchObject hurtObject = new MockTouchObject(160, 112, 0x88); // HURT category
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(hurtObject);

        objectManager.update(0, player, List.of(), 1);

        // Verify applyHurtOrDeath was NOT called (DamageCause overload)
        verify(player, never()).applyHurtOrDeath(anyInt(),
                any(DamageCause.class), anyBoolean());
    }

    @Test
    public void s3kInstaShieldSuppressesHurtWithoutDeflectingShieldReactiveHurtObject() {
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getDoubleJumpFlag()).thenReturn(1);
        when(player.getShieldType()).thenReturn(null);
        when(player.hasShield()).thenReturn(false);

        MockShieldTouchObject projectile = new MockShieldTouchObject(143, 112, 0x88, 0x08);
        setupTableSize(8, 8, 8);
        objectManager.addDynamicObject(projectile);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(projectile.wasShieldDeflected,
                "S3K Insta-Shield temporarily sets Status_Invincible and returns before the shield deflect branch");
        verify(player, never()).applyHurtOrDeath(anyInt(), any(DamageCause.class), anyBoolean());
    }

    @Test
    public void s3kInstaShieldSuppressesMultiRegionHurtDuringExpandedPass() {
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getDoubleJumpFlag()).thenReturn(1);
        when(player.getShieldType()).thenReturn(null);
        when(player.hasShield()).thenReturn(false);

        MockMultiRegionTouchObject flame = new MockMultiRegionTouchObject(
                new TouchResponseProvider.TouchRegion(143, 112, 0x88, 0));
        setupTableSize(8, 8, 8);
        objectManager.addDynamicObject(flame);

        objectManager.update(0, player, List.of(), 1);

        verify(player, never()).applyHurtOrDeath(anyInt(), any(DamageCause.class), anyBoolean());
    }

    @Test
    public void realShieldDeflectsShieldReactiveHurtObject() {
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getDoubleJumpFlag()).thenReturn(0);
        when(player.hasShield()).thenReturn(true);

        MockShieldTouchObject projectile = new MockShieldTouchObject(143, 112, 0x88, 0x08);
        setupTableSize(8, 8, 8);
        objectManager.addDynamicObject(projectile);

        objectManager.update(0, player, List.of(), 1);

        assertTrue(projectile.wasShieldDeflected,
                "S3K ShieldTouchResponse deflects bit-3 shield-reactive harmful objects for real shields");
        verify(player, never()).applyHurtOrDeath(anyInt(), any(DamageCause.class), anyBoolean());
    }

    @Test
    public void s3kInstaShieldSuppressionUsesPreUpdateObjectPositionDuringPostObjectTouchPass() {
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getDoubleJumpFlag()).thenReturn(1);
        when(player.getShieldType()).thenReturn(null);
        when(player.hasShield()).thenReturn(false);

        MockTrackedShieldTouchObject projectile = new MockTrackedShieldTouchObject(
                143, 112,
                220, 112,
                0x88, 0x08);
        setupTableSize(8, 8, 8);
        objectManager.addDynamicObject(projectile);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(projectile.wasShieldDeflected,
                "Post-object touch passes must use the same pre-update object position for Insta-Shield hurt suppression");
        verify(player, never()).applyHurtOrDeath(anyInt(), any(DamageCause.class), anyBoolean());
    }

    @Test
    public void singleRegionTouchResultIncludesProfileShieldReactionFlags() {
        MockShieldTouchObject flame = new MockShieldTouchObject(160, 112, 0x88, 0x10);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(flame);

        objectManager.update(0, player, List.of(), 1);

        assertEquals(0x10, flame.lastResult.shieldReactionFlags(),
                "Single-region dispatch should copy profile shield flags into the touch result");
    }

    @Test
    public void multiRegionTouchResultIncludesTouchedRegionShieldReactionFlags() {
        MockMultiRegionTouchObject flame = new MockMultiRegionTouchObject(
                new TouchResponseProvider.TouchRegion(500, 112, 0x88, 0),
                new TouchResponseProvider.TouchRegion(160, 112, 0x88, 0x10));
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(flame);

        objectManager.update(0, player, List.of(), 1);

        assertEquals(0x10, flame.lastResult.shieldReactionFlags(),
                "Multi-region dispatch should copy the overlapping region shield flags into the touch result");
    }

    @Test
    public void hurtDamageCauseUsesTouchedResultShieldReactionFlags() {
        MockMultiRegionTouchObject flame = new MockMultiRegionTouchObject(
                new TouchResponseProvider.TouchRegion(160, 112, 0x88, 0x10));
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(flame);

        objectManager.update(0, player, List.of(), 1);

        verify(player).applyHurtOrDeath(anyInt(), eq(DamageCause.FIRE), anyBoolean());
    }

    // ==================== Overlap Persistence Tests ====================

    @Test
    public void specialTouchRemainsEdgeTriggeredWhileOverlapPersists() {
        MockTouchObject obj = new MockTouchObject(160, 112, 0x48); // SPECIAL category
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        // First update - should trigger touch
        objectManager.update(0, player, List.of(), 1);
        assertTrue(obj.wasTouched, "First update should trigger touch");

        // Reset touch flag
        obj.wasTouched = false;

        // Second update - still overlapping but should NOT trigger again
        objectManager.update(0, player, List.of(), 1);
        assertFalse(obj.wasTouched, "Second update should NOT trigger touch for same overlap");
    }

    @Test
    public void testContinuousTouchCallbacksTriggerEveryFrameWhenRequested() {
        MockContinuousTouchObject obj = new MockContinuousTouchObject(160, 112, 0x48);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);
        assertTrue(obj.wasTouched, "First update should trigger touch");

        obj.wasTouched = false;
        objectManager.update(0, player, List.of(), 2);
        assertTrue(obj.wasTouched, "Continuous callback object should trigger again while still overlapping");
    }

    @Test
    public void testSkipTouchThisFrameSuppressesOverlap() {
        MockSkipTouchObject obj = new MockSkipTouchObject(160, 112, 0x48);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);

        assertFalse(obj.wasTouched, "Objects flagged skipTouchThisFrame should not trigger touch callbacks");
    }

    @Test
    public void skipSolidContactDoesNotSuppressMultiRegionTouch() {
        MockMultiRegionSkipSolidTouchObject spikes = new MockMultiRegionSkipSolidTouchObject(
                new TouchResponseProvider.TouchRegion(160, 112, 0x88, 0));
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(spikes);

        objectManager.update(0, player, List.of(), 1);

        assertTrue(spikes.wasTouched,
                "skipSolidContactThisFrame is a solid-contact gate and must not suppress multi-region touch");
    }

    @Test
    public void s1RenderFlagTouchSkipsSingleRegionFirstFrameObject() {
        MockSnapshotTouchObject chopperLike = new MockSnapshotTouchObject(160, 112, 0x09);
        setupTableSize(9, 12, 16);
        objectManager.addDynamicObject(chopperLike);

        objectManager.runTouchResponsesForPlayer(player, 1);

        assertFalse(chopperLike.wasTouched,
                "S1 ReactToItem gates single-region touch on obRender bit 7; first-frame objects have not been displayed yet");
    }

    @Test
    public void s1RenderFlagTouchSkipsMultiRegionFirstFrameObject() {
        MockMultiRegionSnapshotTouchObject spikes = new MockMultiRegionSnapshotTouchObject(
                new TouchResponseProvider.TouchRegion(160, 112, 0x88, 0));
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(spikes);

        objectManager.runTouchResponsesForPlayer(player, 1);

        assertFalse(spikes.wasTouched,
                "S1 ReactToItem gates multi-region touch on obRender bit 7 just like single-region touch");
    }

    @Test
    public void multiRegionEnemyTouchTriggersEveryOverlappingFrame() {
        MockMultiRegionTouchObject enemy = new MockMultiRegionTouchObject(
                new TouchResponseProvider.TouchRegion(160, 112, 0x08, 0));
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 1);
        assertTrue(enemy.wasTouched, "First ENEMY overlap frame should dispatch touch");

        enemy.wasTouched = false;
        objectManager.update(0, player, List.of(), 2);

        assertTrue(enemy.wasTouched,
                "ENEMY touch must poll continuously for multi-region objects while overlap persists");
    }

    @Test
    public void singleRegionEnemyTouchTriggersEveryOverlappingFrame() {
        MockTouchObject enemy = new MockTouchObject(160, 112, 0x08);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 1);
        assertTrue(enemy.wasTouched, "First ENEMY overlap frame should dispatch touch");

        enemy.wasTouched = false;
        objectManager.update(0, player, List.of(), 2);

        // Touch_Loop polls ENEMY every frame; only SPECIAL/monitor contacts use
        // the persistent-overlap edge latch (docs/skdisasm/sonic3k.asm:20655-20778).
        assertTrue(enemy.wasTouched,
                "ENEMY touch must poll continuously for single-region objects while overlap persists");
    }

    @Test
    public void testRunTouchResponsesForPlayerUsesLiveCurrentObjectPosition() {
        // Current position barely overlaps; pre-update position does not.
        MockTrackedTouchObject obj = new MockTrackedTouchObject(174, 112, 176, 112, 0x48);
        setupTableSize(8, 6, 6);
        objectManager.addDynamicObject(obj);

        objectManager.runTouchResponsesForPlayer(player, 1);

        assertTrue(obj.wasTouched,
                "Player-slot touch responses should use the live object position because objects have not updated yet");
    }

    @Test
    public void testFrame387MissileGeometryOverlapsWithRollingPlayer() {
        when(player.getCentreX()).thenReturn((short) 0x0241);
        when(player.getCentreY()).thenReturn((short) 0x0394);
        when(player.getYRadius()).thenReturn((short) 14);
        when(player.getRolling()).thenReturn(true);

        MockTrackedTouchObject missile = new MockTrackedTouchObject(
                0x024A, 0x0386,
                0x024C, 0x0384,
                0x87);
        setupTableSize(7, 6, 6);
        objectManager.addDynamicObject(missile);

        objectManager.runTouchResponsesForPlayer(player, 1);

        assertTrue(missile.wasTouched,
                "The GHZ frame-387 missile geometry should overlap Sonic with rolling radii");
    }

    @Test
    public void testS3kInlineTouchSkipsObjectAbsentFromPreviousCollisionResponseList() {
        when(player.getCentreX()).thenReturn((short) 160);
        when(player.getCentreY()).thenReturn((short) 112);
        when(player.getYRadius()).thenReturn((short) 15);
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getAnimationId()).thenReturn(Sonic3kAnimationIds.SPINDASH.id());
        when(player.getYSpeed()).thenReturn((short) 0);

        MockSnapshotAttackableEnemy enemy = new MockSnapshotAttackableEnemy(181, 110, 0x02);
        setupTableSize(2, 12, 12);
        objectManager.addDynamicObject(enemy);

        enemy.setPosition(180, 112);
        objectManager.snapshotTouchResponseState(true);
        objectManager.runTouchResponsesForPlayer(player, 411, true);

        assertFalse(enemy.wasAttacked,
                "S3K TouchResponse walks only the previous Collision_response_list before dynamic objects rebuild it");
        verify(player, never()).setYSpeed(anyShort());
    }

    @Test
    public void testS3kInlineTouchUsesPreviousCollisionResponseListCapturedPosition() {
        when(player.getCentreX()).thenReturn((short) 160);
        when(player.getCentreY()).thenReturn((short) 112);
        when(player.getYRadius()).thenReturn((short) 15);
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getAnimationId()).thenReturn(Sonic3kAnimationIds.SPINDASH.id());
        when(player.getYSpeed()).thenReturn((short) 0);

        MockSnapshotAttackableEnemy enemy = new MockSnapshotAttackableEnemy(181, 110, 0x02);
        setupTableSize(2, 12, 12);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 541, false, true, true);
        objectManager.snapshotTouchResponseState(true);
        enemy.setPosition(180, 112);
        objectManager.runTouchResponsesForPlayer(player, 542, true);

        assertFalse(enemy.wasAttacked,
                "S3K TouchResponse consumes the previous Collision_response_list as captured by the prior object pass");
    }

    @Test
    public void s3kInlineEnemyBounceUsesPreviousCollisionResponseListY() {
        when(player.getCentreX()).thenReturn((short) 160);
        when(player.getCentreY()).thenReturn((short) 0x0491);
        when(player.getYRadius()).thenReturn((short) 15);
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getAnimationId()).thenReturn(Sonic3kAnimationIds.SPINDASH.id());
        when(player.getYSpeed()).thenReturn((short) 0x0400);

        MockDestroyableSnapshotAttackableEnemy enemy =
                new MockDestroyableSnapshotAttackableEnemy(160, 0x0491, 0x02);
        setupTableSize(2, 12, 12);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 5646, false, true, true);
        objectManager.snapshotTouchResponseState(true);
        enemy.setPosition(160, 0x0492);
        objectManager.runTouchResponsesForPlayer(player, 5647, true);

        assertTrue(enemy.wasAttacked, "The previous collision-response-list position should overlap");
        verify(player).setYSpeed((short) 0x0300);
    }

    @Test
    public void multiRegionEnemyBounceUsesMatchedRegionY() {
        when(player.getCentreX()).thenReturn((short) 160);
        when(player.getCentreY()).thenReturn((short) 0x0491);
        when(player.getYRadius()).thenReturn((short) 15);
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getAnimationId()).thenReturn(Sonic3kAnimationIds.SPINDASH.id());
        when(player.getYSpeed()).thenReturn((short) 0x0400);

        MockMultiRegionDestroyableSnapshotEnemy enemy =
                new MockMultiRegionDestroyableSnapshotEnemy(
                        160, 0x0491, new TouchResponseProvider.TouchRegion(160, 0x0491, 0x02));
        setupTableSize(2, 12, 12);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 5646, false, true, true);
        enemy.setPosition(160, 0x0492);
        objectManager.snapshotTouchResponseState(true);
        objectManager.runTouchResponsesForPlayer(player, 5647, true);

        assertTrue(enemy.wasAttacked, "The matched multi-touch region should be attacked");
        verify(player).setYSpeed((short) 0x0300);
    }

    @Test
    public void testS3kPreviousCollisionResponseListCapturesPostObjectUpdatePosition() {
        when(player.getCentreX()).thenReturn((short) 160);
        when(player.getCentreY()).thenReturn((short) 112);
        when(player.getYRadius()).thenReturn((short) 15);
        when(player.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(player.getAnimationId()).thenReturn(Sonic3kAnimationIds.SPINDASH.id());
        when(player.getYSpeed()).thenReturn((short) 0);

        MockMovingSnapshotAttackableEnemy enemy = new MockMovingSnapshotAttackableEnemy(
                178, 112,
                176, 112,
                0x02);
        setupTableSize(2, 8, 8);
        objectManager.addDynamicObject(enemy);

        objectManager.update(0, player, List.of(), 541, false, true, true);
        objectManager.snapshotTouchResponseState(true);
        objectManager.runTouchResponsesForPlayer(player, 542, true);

        assertTrue(enemy.wasAttacked,
                "S3K Collision_response_list is populated after an object's routine/draw-touch helper, "
                        + "so touch must use the post-update position captured with that list");
    }

    @Test
    public void testS3kCollisionResponseListKeepsPublisherAheadOfCamera() {
        MockSnapshotAttackableEnemy enemy = new MockSnapshotAttackableEnemy(0x0300, 112, 0x02);
        ObjectCollisionResponseList responseList = new ObjectCollisionResponseList();

        responseList.captureForNextFrame(List.of(enemy));
        responseList.setUsePrevious(true);

        assertEquals(List.of(enemy), responseList.touchResponseObjects(List.of()),
                "Add_SpriteToCollisionResponseList accepts an explicit publisher regardless of camera distance");
    }

    @Test
    public void testS2InlineTouchUsesFrameStartObjectPositionForAttackableEnemies() {
        when(player.getCentreX()).thenReturn((short) 168);
        when(player.getCentreY()).thenReturn((short) 112);
        when(player.getYRadius()).thenReturn((short) 20);
        when(player.getAnimationId()).thenReturn(ObjectManager.ANIM_ROLL);
        when(player.getRolling()).thenReturn(true);

        MockSnapshotAttackableEnemy enemy = new MockSnapshotAttackableEnemy(190, 112, 0x02);
        setupTableSize(2, 0x0C, 0x14);
        objectManager.addDynamicObject(enemy);

        objectManager.snapshotTouchResponseState(false);
        enemy.setPosition(188, 112);
        objectManager.runTouchResponsesForPlayer(player, 899, true);

        assertFalse(enemy.wasAttacked,
                "S2 TouchResponse runs from the player slot before Obj91_Charge moves; "
                        + "the frame-start position must miss even if the post-update position overlaps");

        objectManager.snapshotTouchResponseState(false);
        objectManager.runTouchResponsesForPlayer(player, 900, true);

        assertTrue(enemy.wasAttacked,
                "On the following player-slot scan, the same object position is now frame-start state and should hit");
    }

    @Test
    public void testRunTouchResponsesRefreshesPreUpdateSnapshotForCurrentFrame() {
        MockSnapshotTouchObject obj = new MockSnapshotTouchObject(300, 112, 0x48);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        // Simulate the end of the previous frame: the saved pre-update snapshot is stale
        // and still points at the old off-screen position.
        obj.snapshotPreUpdatePosition();

        // At the start of the current frame, the object is already overlapping Sonic.
        obj.setPosition(160, 112);

        objectManager.runTouchResponsesForPlayer(player, 1);

        assertTrue(obj.wasTouched,
                "Player-slot touch responses should ignore stale prior-frame snapshots and use the current object position");
    }

    @Test
    public void testTouchTriggersAgainAfterExitAndReenter() {
        MockTouchObject obj = new MockTouchObject(160, 112, 0x48);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        // First update - triggers touch
        objectManager.update(0, player, List.of(), 1);
        obj.wasTouched = false;

        // Move player away
        when(player.getCentreX()).thenReturn((short) 500);
        objectManager.update(0, player, List.of(), 1);

        // Move player back
        when(player.getCentreX()).thenReturn((short) 160);
        objectManager.update(0, player, List.of(), 1);

        assertTrue(obj.wasTouched, "Touch should trigger again after exit and re-enter");
    }

    // ==================== Reset Tests ====================

    @Test
    public void testResetClearsOverlappingSet() {
        MockTouchObject obj = new MockTouchObject(160, 112, 0x48);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);

        objectManager.update(0, player, List.of(), 1);
        obj.wasTouched = false;

        // Reset should clear tracking
        objectManager.resetTouchResponses();

        // Now touch should trigger again
        objectManager.update(0, player, List.of(), 1);
        assertTrue(obj.wasTouched, "Touch should trigger after reset even for same overlap");
    }

    @Test
    public void testResetClearsSidekickOverlapEntries() {
        AbstractPlayableSprite sidekick = mock(AbstractPlayableSprite.class);
        when(sidekick.getCentreX()).thenReturn((short) 160);
        when(sidekick.getCentreY()).thenReturn((short) 112);
        when(sidekick.getYRadius()).thenReturn((short) 20);
        when(sidekick.getCrouching()).thenReturn(false);
        when(sidekick.getDead()).thenReturn(false);
        when(sidekick.getInvulnerable()).thenReturn(false);
        when(sidekick.getCode()).thenReturn("tails");

        MockSnapshotTouchObject obj = new MockSnapshotTouchObject(160, 112, 0x48);
        setupTableSize(8, 16, 16);
        objectManager.addDynamicObject(obj);
        objectManager.snapshotTouchResponseState();

        ObjectTouchResponseController controller = new ObjectTouchResponseController(objectManager, table);
        controller.updateSidekick(sidekick, 1);
        assertEquals(1, controller.captureRewindState().sidekickEntries().size(),
                "Sidekick overlap tracking should be present before reset");

        controller.reset();

        assertTrue(controller.captureRewindState().sidekickEntries().isEmpty(),
                "Reset should drop sidekick overlap entries instead of retaining stale sidekick references");
    }

    // ==================== Helper Classes ====================

    private static Knuckles mockKnucklesTouchPlayer(GameRules rules) {
        Knuckles knuckles = mock(Knuckles.class);
        when(knuckles.getCentreX()).thenReturn((short) 160);
        when(knuckles.getCentreY()).thenReturn((short) 112);
        when(knuckles.getYRadius()).thenReturn((short) 20);
        when(knuckles.getCrouching()).thenReturn(false);
        when(knuckles.getDead()).thenReturn(false);
        when(knuckles.getInvulnerable()).thenReturn(false);
        when(knuckles.getInvincibleFrames()).thenReturn(0);
        when(knuckles.getAnimationId()).thenReturn(Sonic3kAnimationIds.WALK.id());
        when(knuckles.getRolling()).thenReturn(false);
        when(knuckles.getGameRules()).thenReturn(rules);
        return knuckles;
    }

    private static GameRules gameRulesWithPlayerCapability(
            GameRules baseGameRules, GameRules capabilityGameRules) {
        GameRules base = baseGameRules;
        return new GameRules(
                base.playerMovement(),
                capabilityGameRules.playerCapability(),
                base.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble());
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
        public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
            wasTouched = true;
            lastResult = result;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return spawn;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {}

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
        public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
            wasAttacked = true;
        }
    }

    private static class MockContinuousTouchObject extends MockTouchObject {
        public MockContinuousTouchObject(int x, int y, int flags) {
            super(x, y, flags);
        }

        @Override
        public boolean requiresContinuousTouchCallbacks() {
            return true;
        }
    }

    private static final class MockMainOnlyTouchObject extends MockTouchObject {
        private MockMainOnlyTouchObject(int x, int y, int flags) {
            super(x, y, flags);
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return new TouchResponseProfile(
                    TouchCategoryDecodeMode.NORMAL,
                    false,
                    true,
                    multiRegionSource,
                    TouchShieldDeflectCapability.NONE,
                    0,
                    TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                    TouchActorContextPolicy.MAIN_ONLY,
                    TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY);
        }
    }

    private static final class MockShieldTouchObject extends MockTouchObject {
        private final int shieldReactionFlags;
        private boolean wasShieldDeflected;

        private MockShieldTouchObject(int x, int y, int flags, int shieldReactionFlags) {
            super(x, y, flags);
            this.shieldReactionFlags = shieldReactionFlags;
        }

        @Override
        public int getShieldReactionFlags() {
            return shieldReactionFlags;
        }

        @Override
        public boolean onShieldDeflect(PlayableEntity player) {
            wasShieldDeflected = true;
            return true;
        }
    }

    private static final class MockMultiRegionTouchObject extends MockTouchObject {
        private final TouchResponseProvider.TouchRegion[] regions;

        private MockMultiRegionTouchObject(TouchResponseProvider.TouchRegion... regions) {
            super(0, 0, 0);
            this.regions = regions;
        }

        @Override
        public TouchResponseProvider.TouchRegion[] getMultiTouchRegions() {
            return regions;
        }
    }

    private static final class MockMultiRegionSkipSolidTouchObject extends MockTouchObject {
        private final TouchResponseProvider.TouchRegion[] regions;

        private MockMultiRegionSkipSolidTouchObject(TouchResponseProvider.TouchRegion... regions) {
            super(0, 0, 0);
            this.regions = regions;
        }

        @Override
        public boolean isSkipSolidContactThisFrame() {
            return true;
        }

        @Override
        public TouchResponseProvider.TouchRegion[] getMultiTouchRegions() {
            return regions;
        }
    }

    private static class MockS3kTouchSpecialObject extends MockTouchObject {
        public MockS3kTouchSpecialObject(int x, int y, int flags) {
            super(x, y, flags);
        }

        @Override
        public boolean usesS3kTouchSpecialPropertyResponse() {
            return true;
        }
    }

    private static class MockContinuousS3kTouchSpecialObject extends MockTouchObject {
        public MockContinuousS3kTouchSpecialObject(int x, int y, int flags) {
            super(x, y, flags);
        }

        @Override
        public boolean usesS3kTouchSpecialPropertyResponse() {
            return true;
        }

        @Override
        public boolean requiresContinuousTouchCallbacks() {
            return true;
        }
    }

    private static class MockSkipTouchObject extends MockTouchObject {
        public MockSkipTouchObject(int x, int y, int flags) {
            super(x, y, flags);
        }

        @Override
        public boolean isSkipTouchThisFrame() {
            return true;
        }
    }

    private static class MockTrackedTouchObject extends MockTouchObject {
        private final int currentX;
        private final int currentY;
        private final int preUpdateX;
        private final int preUpdateY;

        public MockTrackedTouchObject(int currentX, int currentY, int preUpdateX, int preUpdateY, int flags) {
            super(currentX, currentY, flags);
            this.currentX = currentX;
            this.currentY = currentY;
            this.preUpdateX = preUpdateX;
            this.preUpdateY = preUpdateY;
        }

        @Override
        public int getX() {
            return currentX;
        }

        @Override
        public int getY() {
            return currentY;
        }

        @Override
        public int getPreUpdateX() {
            return preUpdateX;
        }

        @Override
        public int getPreUpdateY() {
            return preUpdateY;
        }
    }

    private static final class MockTrackedAttackableEnemy extends MockTrackedTouchObject
            implements TouchResponseAttackable {
        boolean wasAttacked = false;

        private MockTrackedAttackableEnemy(int currentX, int currentY, int preUpdateX, int preUpdateY, int flags) {
            super(currentX, currentY, preUpdateX, preUpdateY, flags);
        }

        @Override
        public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
            wasAttacked = true;
        }
    }

    private static class MockSnapshotAttackableEnemy extends AbstractObjectInstance
            implements TouchResponseProvider, TouchResponseAttackable {
        private int currentX;
        private int currentY;
        private final int collisionFlags;
        boolean wasAttacked = false;

        private MockSnapshotAttackableEnemy(int x, int y, int flags) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "MockSnapshotAttackableEnemy");
            this.currentX = x;
            this.currentY = y;
            this.collisionFlags = flags;
        }

        void setPosition(int x, int y) {
            this.currentX = x;
            this.currentY = y;
        }

        @Override
        public int getX() {
            return currentX;
        }

        @Override
        public int getY() {
            return currentY;
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
        public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
            wasAttacked = true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static class MockDestroyableSnapshotAttackableEnemy
            extends MockSnapshotAttackableEnemy {
        private MockDestroyableSnapshotAttackableEnemy(int x, int y, int flags) {
            super(x, y, flags);
        }

        @Override
        public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
            super.onPlayerAttack(player, result);
            setDestroyed(true);
        }
    }

    private static final class MockMultiRegionDestroyableSnapshotEnemy
            extends MockDestroyableSnapshotAttackableEnemy {
        private final TouchResponseProvider.TouchRegion[] regions;

        private MockMultiRegionDestroyableSnapshotEnemy(
                int x, int y, TouchResponseProvider.TouchRegion... regions) {
            super(x, y, 0);
            this.regions = regions;
        }

        @Override
        public TouchResponseProvider.TouchRegion[] getMultiTouchRegions() {
            return regions;
        }
    }

    private static final class MockMovingSnapshotAttackableEnemy extends AbstractObjectInstance
            implements TouchResponseProvider, TouchResponseAttackable {
        private int currentX;
        private int currentY;
        private final int movedX;
        private final int movedY;
        private final int collisionFlags;
        boolean wasAttacked = false;

        private MockMovingSnapshotAttackableEnemy(int initialX, int initialY, int movedX, int movedY, int flags) {
            super(new ObjectSpawn(initialX, initialY, 0, 0, 0, false, 0), "MockMovingSnapshotAttackableEnemy");
            this.currentX = initialX;
            this.currentY = initialY;
            this.movedX = movedX;
            this.movedY = movedY;
            this.collisionFlags = flags;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            currentX = movedX;
            currentY = movedY;
        }

        @Override
        public int getX() {
            return currentX;
        }

        @Override
        public int getY() {
            return currentY;
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
        public boolean usesCurrentTouchResponseState() {
            return true;
        }

        @Override
        public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
            wasAttacked = true;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class MockTrackedShieldTouchObject extends MockTrackedTouchObject {
        private final int shieldReactionFlags;
        private boolean wasShieldDeflected;

        private MockTrackedShieldTouchObject(int currentX, int currentY, int preUpdateX, int preUpdateY,
                int flags, int shieldReactionFlags) {
            super(currentX, currentY, preUpdateX, preUpdateY, flags);
            this.shieldReactionFlags = shieldReactionFlags;
        }

        @Override
        public int getShieldReactionFlags() {
            return shieldReactionFlags;
        }

        @Override
        public boolean onShieldDeflect(PlayableEntity player) {
            wasShieldDeflected = true;
            return true;
        }
    }

    private static class MockSnapshotTouchObject extends AbstractObjectInstance
            implements TouchResponseProvider, TouchResponseListener {
        private int currentX;
        private int currentY;
        private final int collisionFlags;
        boolean wasTouched = false;

        private MockSnapshotTouchObject(int x, int y, int flags) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "MockSnapshotTouchObject");
            this.currentX = x;
            this.currentY = y;
            this.collisionFlags = flags;
        }

        void setPosition(int x, int y) {
            this.currentX = x;
            this.currentY = y;
        }

        @Override
        public int getX() {
            return currentX;
        }

        @Override
        public int getY() {
            return currentY;
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
        public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
            wasTouched = true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class MockMultiRegionSnapshotTouchObject extends AbstractObjectInstance
            implements TouchResponseProvider, TouchResponseListener {
        private final TouchResponseProvider.TouchRegion[] regions;
        boolean wasTouched = false;

        private MockMultiRegionSnapshotTouchObject(TouchResponseProvider.TouchRegion... regions) {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "MockMultiRegionSnapshotTouchObject");
            this.regions = regions;
        }

        @Override
        public int getCollisionFlags() {
            return 0;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public TouchResponseProvider.TouchRegion[] getMultiTouchRegions() {
            return regions;
        }

        @Override
        public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
            wasTouched = true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
