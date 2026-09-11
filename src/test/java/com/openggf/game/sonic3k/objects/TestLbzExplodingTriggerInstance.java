package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLbzExplodingTriggerInstance {

    @BeforeEach
    void resetTriggers() {
        Sonic3kLevelTriggerManager.reset();
    }

    @Test
    void registryRoutesS3klSlot13ToLbzExplodingTrigger() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);

        ObjectInstance trigger = registry.create(new ObjectSpawn(
                0x1800, 0x0600, 0x13, 0, 0, false, 0));

        assertFalse(trigger instanceof PlaceholderObjectInstance,
                "S3KL slot $13 is Obj_LBZExplodingTrigger and must not remain a placeholder");
        assertEquals("LBZExplodingTrigger", trigger.getName());
        assertInstanceOf(TouchResponseProvider.class, trigger,
                "Obj_LBZExplodingTrigger uses collision_flags $C6 and Touch_Special property bits");
    }

    @Test
    void registryKeepsSklSlot13AsMhzMushroomCatapult() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance catapult = registry.create(new ObjectSpawn(
                0x1800, 0x0600, 0x13, 0, 0, false, 0));

        assertFalse(catapult instanceof PlaceholderObjectInstance);
        assertEquals("MHZMushroomCatapult", catapult.getName(),
                "S3K object slot $13 is zone-set-specific; SKL/MHZ must keep Obj_MHZMushroomCatapult");
    }

    @Test
    void touchProfileUsesS3kSpecialPropertyCollisionFlags() {
        LbzExplodingTriggerInstance trigger = new LbzExplodingTriggerInstance(new ObjectSpawn(
                0x1800, 0x0600, 0x13, 0, 0, false, 0));

        TouchResponseProfile profile = trigger.getTouchResponseProfile(false);

        assertEquals(0xC6, trigger.getCollisionFlags());
        assertEquals(TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY, profile.categoryDecodeMode());
        assertFalse(profile.continuousCallbacks());
        assertTrue(profile.requiresRenderFlagForTouch());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    void renderUsesObjectPlacementFlipBits() {
        PatternSpriteRenderer leftFacingRenderer = readyRenderer();
        LbzExplodingTriggerInstance leftFacing = triggerForRenderFlags(0);
        leftFacing.setServices(new RenderingServices(rendererManager(leftFacingRenderer)));

        leftFacing.appendRenderCommands(new java.util.ArrayList<>());

        verify(leftFacingRenderer).drawFrameIndex(eq(0), eq(0x1800), eq(0x0600), eq(false), eq(false));

        PatternSpriteRenderer rightFacingRenderer = readyRenderer();
        LbzExplodingTriggerInstance rightFacing = triggerForRenderFlags(1);
        rightFacing.setServices(new RenderingServices(rendererManager(rightFacingRenderer)));

        rightFacing.appendRenderCommands(new java.util.ArrayList<>());

        verify(rightFacingRenderer).drawFrameIndex(eq(0), eq(0x1800), eq(0x0600), eq(true), eq(false));
    }

    @Test
    void rollingTouchNegatesVelocityTogglesTriggerAndBecomesExplosion() {
        LbzExplodingTriggerInstance trigger = new LbzExplodingTriggerInstance(new ObjectSpawn(
                0x1800, 0x0600, 0x13, 0x05, 0, false, 0));
        trigger.setServices(new TestObjectServices());
        AbstractPlayableSprite player = s3kPlayer();
        player.setAnimationId(Sonic3kAnimationIds.ROLL.id());
        player.setXSpeed((short) 0x0280);
        player.setYSpeed((short) -0x0180);

        trigger.onTouchResponse(player, new TouchResponseResult(0x06, 0x10, 0x10, TouchCategory.SPECIAL), 0);
        trigger.update(0, player);

        assertEquals((short) -0x0280, player.getXSpeed(),
                "sub_25D2C negates x_vel(a1) when anim(a1) == 2");
        assertEquals((short) 0x0180, player.getYSpeed(),
                "sub_25D2C negates y_vel(a1) when anim(a1) == 2");
        assertTrue(Sonic3kLevelTriggerManager.testBit(5, 0),
                "bchg #0,Level_trigger_array[subtype & $F] toggles bit 0 on the first hit");
        assertEquals(0, trigger.getCollisionFlags(),
                "Obj_LBZExplodingTrigger clears collision_flags after becoming Obj_Explosion");
        assertEquals(0, trigger.getCollisionProperty());
        assertTrue(trigger.isExplodingForTest());
    }

    @Test
    void nonRollingTouchOnlyConsumesCollisionProperty() {
        LbzExplodingTriggerInstance trigger = new LbzExplodingTriggerInstance(new ObjectSpawn(
                0x1800, 0x0600, 0x13, 0x05, 0, false, 0));
        trigger.setServices(new TestObjectServices());
        AbstractPlayableSprite player = s3kPlayer();
        player.setAnimationId(Sonic3kAnimationIds.WALK.id());
        player.setXSpeed((short) 0x0280);
        player.setYSpeed((short) -0x0180);

        trigger.onTouchResponse(player, new TouchResponseResult(0x06, 0x10, 0x10, TouchCategory.SPECIAL), 0);
        trigger.update(0, player);

        assertEquals((short) 0x0280, player.getXSpeed());
        assertEquals((short) -0x0180, player.getYSpeed());
        assertFalse(Sonic3kLevelTriggerManager.testAny(5));
        assertEquals(0xC6, trigger.getCollisionFlags());
        assertEquals(0, trigger.getCollisionProperty(),
                "loc_25CF0 bclr consumes collision_property even when anim(a1) != 2");
        assertFalse(trigger.isExplodingForTest());
    }

    @Test
    void nativeP2TouchUsesCollisionPropertyBit1EvenWhenNotCpuControlled() {
        LbzExplodingTriggerInstance trigger = new LbzExplodingTriggerInstance(new ObjectSpawn(
                0x1800, 0x0600, 0x13, 0x05, 0, false, 0));
        AbstractPlayableSprite main = s3kPlayer();
        AbstractPlayableSprite sidekick = new Sonic("sonic_p2", (short) 0x1800, (short) 0x0600);
        sidekick.setAnimationId(Sonic3kAnimationIds.ROLL.id());
        sidekick.setXSpeed((short) -0x0200);
        sidekick.setYSpeed((short) 0x0100);
        trigger.setServices(new TestObjectServices().withSidekicks(java.util.List.of(sidekick)));

        trigger.onTouchResponse(sidekick, new TouchResponseResult(0x06, 0x10, 0x10, TouchCategory.SPECIAL), 0);
        trigger.update(0, main);

        assertEquals((short) 0x0200, sidekick.getXSpeed(),
                "loc_25D18 consumes collision_property bit 1 for Player_2, independent of CPU control");
        assertEquals((short) -0x0100, sidekick.getYSpeed());
        assertTrue(Sonic3kLevelTriggerManager.testBit(5, 0));
        assertTrue(trigger.isExplodingForTest());
    }

    @Test
    void lbzPlanIncludesExplodingTriggerLevelArt() {
        var plan = com.openggf.game.sonic3k.Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_LBZ, 0);

        var trigger = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_EXPLODING_TRIGGER))
                .findFirst().orElse(null);

        assertNotNull(trigger, "Obj_LBZExplodingTrigger uses resident LBZ misc art");
        assertEquals(Sonic3kConstants.MAP_LBZ_EXPLODING_TRIGGER_ADDR, trigger.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ_MISC + 0x70, trigger.artTileBase());
        assertEquals(2, trigger.palette());
    }

    private static AbstractPlayableSprite s3kPlayer() {
        return new Sonic("sonic", (short) 0x1800, (short) 0x0600);
    }

    private static LbzExplodingTriggerInstance triggerForRenderFlags(int renderFlags) {
        return new LbzExplodingTriggerInstance(new ObjectSpawn(
                0x1800, 0x0600, 0x13, 0, renderFlags, false, 0));
    }

    private static PatternSpriteRenderer readyRenderer() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        return renderer;
    }

    private static ObjectRenderManager rendererManager(PatternSpriteRenderer renderer) {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ_EXPLODING_TRIGGER)).thenReturn(renderer);
        return renderManager;
    }

    private static final class RenderingServices extends TestObjectServices {
        private final ObjectRenderManager renderManager;

        private RenderingServices(ObjectRenderManager renderManager) {
            this.renderManager = renderManager;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }
}
