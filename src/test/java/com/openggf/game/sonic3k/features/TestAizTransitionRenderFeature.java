package com.openggf.game.sonic3k.features;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizTransitionRenderFeature {

    @Test
    void resetPreservesFireDescriptorCacheForSeamlessAizFireContinuation() throws Exception {
        AizTransitionRenderFeature feature = new AizTransitionRenderFeature();
        AizFireCurtainRenderer renderer = fireCurtainRenderer(feature);
        markFireDescriptorsCached(renderer);

        feature.reset();

        assertTrue(isFireDescriptorsCached(renderer),
                "ZoneFeatureProvider reset runs during the AIZ1->AIZ2 seamless reload and must not discard cached fire descriptors");
    }

    @Test
    void nonAizZoneInitClearsFireDescriptorCache() throws Exception {
        AizTransitionRenderFeature feature = new AizTransitionRenderFeature();
        AizFireCurtainRenderer renderer = fireCurtainRenderer(feature);
        markFireDescriptorsCached(renderer);

        feature.onZoneInit(Sonic3kZoneIds.ZONE_HCZ, 0);

        assertFalse(isFireDescriptorsCached(renderer),
                "Starting a non-AIZ zone should clear stale AIZ fire descriptors");
    }

    private static AizFireCurtainRenderer fireCurtainRenderer(AizTransitionRenderFeature feature) throws Exception {
        Field field = AizTransitionRenderFeature.class.getDeclaredField("fireCurtainRenderer");
        field.setAccessible(true);
        return (AizFireCurtainRenderer) field.get(feature);
    }

    private static void markFireDescriptorsCached(AizFireCurtainRenderer renderer) throws Exception {
        Field descriptors = AizFireCurtainRenderer.class.getDeclaredField("cachedFireDescriptors");
        descriptors.setAccessible(true);
        descriptors.set(renderer, new int[][] {{0x6500}});

        Field cached = AizFireCurtainRenderer.class.getDeclaredField("fireDescriptorsCached");
        cached.setAccessible(true);
        cached.setBoolean(renderer, true);
    }

    private static boolean isFireDescriptorsCached(AizFireCurtainRenderer renderer) throws Exception {
        Field cached = AizFireCurtainRenderer.class.getDeclaredField("fireDescriptorsCached");
        cached.setAccessible(true);
        return cached.getBoolean(renderer);
    }
}
