package com.openggf.game.sonic3k.objects;

import com.openggf.game.DamageCause;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestIczHarmfulIceObjectInstance {

    // Clear any gameplay session leaked by a prior test in this fork so the registry
    // resolves the S3KL zone set (not a leaked SKL zone). Parallel-suite flake fix.
    @BeforeEach
    void clearLeakedGameplaySession() {
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void registryCreatesIczHarmfulIceForId0xB8InS3klZoneSet() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x1200, 0x0680, Sonic3kObjectIds.ICZ_HARMFUL_ICE, 0, 0, false, 0));

        assertInstanceOf(IczHarmfulIceObjectInstance.class, instance);
    }

    @Test
    void subtypeZeroIsStaticHurtIceShard() {
        TestableIczHarmfulIce ice = new TestableIczHarmfulIce(
                new ObjectSpawn(0x1200, 0x0680, Sonic3kObjectIds.ICZ_HARMFUL_ICE, 0, 0, false, 0),
                mock(PatternSpriteRenderer.class));

        assertEquals(0x82, ice.getCollisionFlags());
        assertEquals(0, ice.getCollisionProperty());
        assertEquals(0x1200, ice.getX());
        assertEquals(0x0680, ice.getY());
        assertEquals(5, ice.getPriorityBucket());

        ice.appendRenderCommands(new ArrayList<>());

        verify(ice.renderer).drawFrameIndex(5, 0x1200, 0x0680, false, false, 2);
    }

    @Test
    void nonzeroSubtypeUsesSpecialTouchBreakCollision() {
        IczHarmfulIceObjectInstance ice = new IczHarmfulIceObjectInstance(
                new ObjectSpawn(0x1200, 0x0680, Sonic3kObjectIds.ICZ_HARMFUL_ICE, 1, 0, false, 0));

        assertEquals(0xD7, ice.getCollisionFlags());
        assertEquals(1, ((TouchResponseProvider) ice).getMultiTouchRegions().length);
        TouchResponseProfile profile = ((TouchResponseProvider) ice).getTouchResponseProfile(true);
        assertEquals(TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY, profile.categoryDecodeMode());
        assertTrue(profile.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    void subtypeZeroUsesNormalSingleRegionTouchProfile() {
        IczHarmfulIceObjectInstance ice = new IczHarmfulIceObjectInstance(
                new ObjectSpawn(0x1200, 0x0680, Sonic3kObjectIds.ICZ_HARMFUL_ICE, 0, 0, false, 0));

        TouchResponseProfile profile = ((TouchResponseProvider) ice).getTouchResponseProfile(false);

        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertNull(((TouchResponseProvider) ice).getMultiTouchRegions());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    void harmfulIceSurvivesSpawnWindowBeforeItBecomesVisible() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        IczHarmfulIceObjectInstance ice = new IczHarmfulIceObjectInstance(
                new ObjectSpawn(0x0180, 0x0080, Sonic3kObjectIds.ICZ_HARMFUL_ICE, 0, 0, false, 0));

        ice.update(0, null);

        assertFalse(ice.isDestroyed(),
                "Obj_WaitOffscreen objects can exist in the load window before render_flags marks them visible");
    }

    @Test
    void specialTouchHurtsPlayerBreaksIceSpawnsTwelveDebrisAndPlaysIceSpikes() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<Integer> sfx = new ArrayList<>();
        IczHarmfulIceObjectInstance ice = new IczHarmfulIceObjectInstance(
                new ObjectSpawn(0x1200, 0x0680, Sonic3kObjectIds.ICZ_HARMFUL_ICE, 1, 0, false, 0));
        ice.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public void playSfx(int soundId) {
                sfx.add(soundId);
            }
        });
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getInvincibleFrames()).thenReturn(0);

        ice.onTouchResponse(player, specialResult(), 123);

        // Shipped FixBugs=0 loc_8B4F8 omits the invulnerability_timer test, so the
        // hurt path ignores post-hit i-frames (sonic3k.asm:189765-189775).
        verify(player).applyHurtIgnoringIFrames(0x1200, DamageCause.SPIKE);
        assertTrue(ice.isDestroyed());
        assertEquals(List.of(Sonic3kSfx.ICE_SPIKES.id), sfx);
        ArgumentCaptor<ObjectInstance> captor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, times(12)).addDynamicObjectAfterCurrent(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .allMatch(IczHarmfulIceObjectInstance.IceDebris.class::isInstance));
    }

    @Test
    void flashingPlayerIsStillHurtBecauseRetailOmitsTheInvulnerabilityCheck() {
        ObjectManager objectManager = mock(ObjectManager.class);
        IczHarmfulIceObjectInstance ice = new IczHarmfulIceObjectInstance(
                new ObjectSpawn(0x1200, 0x0680, Sonic3kObjectIds.ICZ_HARMFUL_ICE, 1, 0, false, 0));
        ice.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getInvincibleFrames()).thenReturn(0);

        ice.onTouchResponse(player, specialResult(), 123);

        // FixBugs=0 (docs/skdisasm/sonic3k.asm:38): loc_8B4F8 has no
        // invulnerability_timer test, so a flashing character is hurt again.
        verify(player).applyHurtIgnoringIFrames(0x1200, DamageCause.SPIKE);
    }

    @Test
    void invinciblePlayerStillBreaksIceWithoutApplyingHurt() {
        ObjectManager objectManager = mock(ObjectManager.class);
        IczHarmfulIceObjectInstance ice = new IczHarmfulIceObjectInstance(
                new ObjectSpawn(0x1200, 0x0680, Sonic3kObjectIds.ICZ_HARMFUL_ICE, 1, 0, false, 0));
        ice.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getInvincibleFrames()).thenReturn(1);

        ice.onTouchResponse(player, specialResult(), 123);

        verify(player, never()).applyHurtIgnoringIFrames(
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
        assertTrue(ice.isDestroyed());
        verify(objectManager, times(12)).addDynamicObjectAfterCurrent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void harmfulIceDebrisSpecsUseCreateChild6SimpleSubtypesAndIndexedVelocities() {
        List<IczHarmfulIceObjectInstance.IceDebrisSpec> specs =
                IczHarmfulIceObjectInstance.debrisSpecsForTesting(0x1200, 0x0680, false);

        assertEquals(12, specs.size());
        assertEquals(new IczHarmfulIceObjectInstance.IceDebrisSpec(0, 0x1200, 0x0680, -0x100, -0x100), specs.get(0));
        assertEquals(new IczHarmfulIceObjectInstance.IceDebrisSpec(2, 0x1200, 0x0680, 0x100, -0x100), specs.get(1));
        assertEquals(new IczHarmfulIceObjectInstance.IceDebrisSpec(10, 0x1200, 0x0680, 0x300, -0x200), specs.get(5));
        assertEquals(new IczHarmfulIceObjectInstance.IceDebrisSpec(22, 0x1200, 0x0680, -0x400, -0x300), specs.get(11));
    }

    @Test
    void profileMarksIczHarmfulIceImplementedForS3klOnly() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();

        assertTrue(profile.getImplementedIds().contains(Sonic3kObjectIds.ICZ_HARMFUL_ICE));
    }

    private static TouchResponseResult specialResult() {
        return new TouchResponseResult(0x17, 0x10, 0x10, TouchCategory.SPECIAL);
    }

    private static final class TestableIczHarmfulIce extends IczHarmfulIceObjectInstance {
        private final PatternSpriteRenderer renderer;

        private TestableIczHarmfulIce(ObjectSpawn spawn, PatternSpriteRenderer renderer) {
            super(spawn);
            this.renderer = renderer;
        }

        @Override
        protected PatternSpriteRenderer getRenderer(String artKey) {
            assertEquals(Sonic3kObjectArtKeys.ICZ_PLATFORMS, artKey);
            return renderer;
        }
    }
}
